package com.bidpulse.infra.redis;

import lombok.extern.slf4j.Slf4j;
import com.bidpulse.config.BidPulseProperties;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bidpulse.entity.Ad;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算服务
 *
 * <p><b>M3 实现：Lua 原子扣减</b>。把「读余额 → 校验 → 扣减 → 记消耗」收进
 * 一个 Lua 脚本，由 Redis 单线程原子执行，消除 M1 两步写法的并发超扣窗口。
 *
 * <p><b>为什么 M1 没这么做</b>：M1 用 {@code GET → 比较 → decrement} 三步、两次往返，
 * 是最直观的写法。并发下请求 A、B 同时读到余额 10 元，都通过校验、各自扣 8 元，
 * 余额变成 -6 元，超扣发生。这一段「先量 baseline、再针对性加固」正是简历上
 * 「由 A 降到 B」的证据链：M1 暴露问题 → M3 用 Lua 把超扣压到 0。
 *
 * <p><b>Key 命名</b>：{@code budget:{adId}:{yyyyMMdd}} 剩余预算(分)，
 * {@code spent:{adId}:{yyyyMMdd}} 累计消耗(分)。按天分桶让过期策略自然生效。
 * 金额统一以「分」为单位存 Redis，避免 double 浮点误差。
 *
 * <p><b>M12：双 key 丢失时的冷启动重建</b>。此前只覆盖「budget 单独丢失」，
 * 若 budget 与 spent 同时丢失（Redis 无持久化重启 / FLUSH），当天已消耗会被归零、
 * 额度被重新放满。现在 Lua 在双 key 都缺失时返回 {@link DeductResult#NEEDS_SEED}，
 * 由调用方用 {@code billing_event} 当日汇总重新播种，再重试扣减。
 *
 * <p><b>P1：实时频次控制</b>。预算校验/扣减与频次校验/自增在同一个 Lua 脚本内完成，
 * 不额外增加一次 Redis 往返，也不会出现「频次已计、预算没扣」的半成品状态。
 * 频次键为 {@code freq:u:{userId}:{yyyyMMddHH}} 的 Hash，field=adId，天然按小时分窗、
 * 按广告隔离；未传 {@code userId} 时整段频次逻辑短路，行为与未启用时一致。
 */
@Slf4j
@Service
public class BudgetService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /**
     * 扣减结果。
     *
     * <ul>
     *   <li>{@link #SUCCESS}：扣减成功；</li>
     *   <li>{@link #INSUFFICIENT}：余额不足（调用方可回写 ad.status=3）；</li>
     *   <li>{@link #NEEDS_SEED}：budget 与 spent 都不存在，无法判定当日已消耗，
     *       需要用 DB 账单重建后再重试；</li>
     *   <li>{@link #FREQ_CAPPED}：该用户本小时对该广告的成交次数已达上限。
     *       这是正常业务结果，<b>不是</b>余额不足——调用方应跳过该候选，
     *       但绝不能回写掉 {@code ad.status}。</li>
     * </ul>
     */
    public enum DeductResult {
        SUCCESS,
        INSUFFICIENT,
        NEEDS_SEED,
        FREQ_CAPPED
    }

    /**
     * 原子预算扣减 + 频次控制脚本。
     * KEYS[1] = budget:{adId}:{date} 剩余预算(分)
     * KEYS[2] = spent:{adId}:{date}  累计消耗(分)
     * KEYS[3] = freq:u:{userId}:{yyyyMMddHH} 用户频次 Hash（field=adId，value=成交次数）
     * ARGV[1] = 本次扣减额(分)
     * ARGV[2] = 日预算(分)，key 缺失时用于播种
     * ARGV[3] = 预算 key 的 TTL(秒)
     * ARGV[4] = 频次开关：'1' 启用 / '0' 关闭（未传 userId 或配置关闭）
     * ARGV[5] = 频次 Hash 的 field（adId）
     * ARGV[6] = 每小时最大成交次数
     * ARGV[7] = 频次 key 的 TTL(秒)
     * 返回：1 = 扣减成功；0 = 余额不足；-1 = 双 key 都缺失，需要 DB 重建；2 = 频次超限。
     */
    private static final String DEDUCT_LUA =
            // 先做频次判断：被频次拦下时不触碰预算 key，也不会走到 NEEDS_SEED 的 DB 重建。
            // 放在预算扣减之后判断的话，被频次拦下的那一次已经扣了钱，无法回滚。
            "if ARGV[4] == '1' then\n" +
            "  local count = tonumber(redis.call('HGET', KEYS[3], ARGV[5]))\n" +
            "  if count and count >= tonumber(ARGV[6]) then\n" +
            "    return 2\n" +
            "  end\n" +
            "end\n" +
            "local bal = redis.call('GET', KEYS[1])\n" +
            "local spentRaw = redis.call('GET', KEYS[2])\n" +
            // 双 key 都缺失：无法知道今天已经花掉多少，绝不能按「整笔日预算」放款。
            // 返回 -1 交给调用方用 billing_event 当日汇总重建，重建后再重试扣减。
            "if not bal and not spentRaw then\n" +
            "  return -1\n" +
            "end\n" +
            "local ttl = tonumber(ARGV[3])\n" +
            // spent 可能是非整数脏值（人工改 key / 迁移残留）：
            // tonumber 失败按 0 处理，并向下取整、钳到非负，保证后续写回的一定是合法整数。
            "local spent = tonumber(spentRaw)\n" +
            "if not spent then spent = 0 end\n" +
            "spent = math.floor(spent)\n" +
            "if spent < 0 then spent = 0 end\n" +
            "if not bal then\n" +
            // budget key 单独丢失（重启 / FLUSH / 淘汰），此时 spent 还在：
            // 用 spent 反推当日真实剩余，避免已经花掉的钱被重新放出。
            "  local daily = tonumber(ARGV[2])\n" +
            "  bal = daily - spent\n" +
            "  if bal < 0 then bal = 0 end\n" +
            "  redis.call('SET', KEYS[1], bal, 'EX', ttl)\n" +
            "end\n" +
            "local amt = tonumber(ARGV[1])\n" +
            "local remain = tonumber(bal)\n" +
            "if remain < amt then\n" +
            "  return 0\n" +
            "end\n" +
            "redis.call('DECRBY', KEYS[1], amt)\n" +
            // spent 的 TTL 必须与 budget 对齐：若 spent 先过期，下一次扣减会把它重建为 0，
            // applyDailyBudget 就会按「今日未消费」重新放款。用 budget 的实际剩余 TTL 同步；
            // TTL <= 0（无过期 / 即将过期）时退回配置的全量 TTL。
            // 直接 SET「已消耗 + 本次」而不是 INCRBY：脚本报错不会回滚已执行的 DECRBY，
            // 脏值上 INCRBY 失败会变成「预算已扣、消耗没记」，所以这里顺手把脏值覆盖掉。
            "local spentTtl = redis.call('TTL', KEYS[1])\n" +
            "if spentTtl < 1 then spentTtl = ttl end\n" +
            "redis.call('SET', KEYS[2], spent + amt, 'EX', spentTtl)\n" +
            // 只有扣减成功才计数：余额不足/需要重建都不占用用户的频次额度。
            // 首次创建 field 时才设 TTL，避免每次成交都延长整个小时键的寿命。
            "if ARGV[4] == '1' then\n" +
            "  local newCount = redis.call('HINCRBY', KEYS[3], ARGV[5], 1)\n" +
            "  if newCount == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[7]))\n" +
            "  end\n" +
            "end\n" +
            "return 1\n";

    private static final RedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>(DEDUCT_LUA, Long.class);

    /**
     * 冷启动播种脚本：用 DB 当日账单重建 budget / spent。
     * KEYS[1] = budget:{adId}:{date}
     * KEYS[2] = spent:{adId}:{date}
     * ARGV[1] = 日预算(分)
     * ARGV[2] = DB 当日已消耗(分)
     * ARGV[3] = key TTL(秒)
     * 返回：播种后（或并发请求已写入）的剩余额度(分)。
     *
     * <p>两个 SET 都用 {@code NX}：与并发扣减/并发播种竞争时，绝不覆盖已经生效的状态；
     * 调用方播种后重试一次扣减，由扣减脚本基于最终值做判定。
     */
    private static final String SEED_LUA =
            "local daily = tonumber(ARGV[1])\n" +
            "local spent = math.floor(tonumber(ARGV[2]))\n" +
            "if spent < 0 then spent = 0 end\n" +
            "local bal = daily - spent\n" +
            "if bal < 0 then bal = 0 end\n" +
            "local ttl = tonumber(ARGV[3])\n" +
            "redis.call('SET', KEYS[1], bal, 'EX', ttl, 'NX')\n" +
            "redis.call('SET', KEYS[2], spent, 'EX', ttl, 'NX')\n" +
            "return tonumber(redis.call('GET', KEYS[1]))\n";

    private static final RedisScript<Long> SEED_SCRIPT = new DefaultRedisScript<>(SEED_LUA, Long.class);

    /**
     * 对账修复脚本：当 Redis spent 低于 DB 账单时，按账单重算（只升不降）。
     * KEYS[1] = budget:{adId}:{date}
     * KEYS[2] = spent:{adId}:{date}
     * ARGV[1] = 日预算(分)
     * ARGV[2] = DB 当日已消耗(分)
     * ARGV[3] = key TTL(秒)
     * 返回：1 = 修复成功；0 = 当前 spent 已不低于账单（无需修复）。
     *
     * <p>在脚本内重新读取 spent：对账任务读取与修复之间可能发生扣减，
     * 若当前值已经被并发请求推到账单之上，绝不能把它改小。
     */
    private static final String REPAIR_LUA =
            "local daily = tonumber(ARGV[1])\n" +
            "local dbSpent = math.floor(tonumber(ARGV[2]))\n" +
            "if dbSpent < 0 then dbSpent = 0 end\n" +
            "local cur = tonumber(redis.call('GET', KEYS[2]))\n" +
            "if not cur then cur = 0 end\n" +
            "cur = math.floor(cur)\n" +
            "if cur < 0 then cur = 0 end\n" +
            "if cur >= dbSpent then return 0 end\n" +
            "local bal = daily - dbSpent\n" +
            "if bal < 0 then bal = 0 end\n" +
            "local ttl = tonumber(ARGV[3])\n" +
            "redis.call('SET', KEYS[1], bal, 'EX', ttl)\n" +
            "redis.call('SET', KEYS[2], dbSpent, 'EX', ttl)\n" +
            "return 1\n";

    private static final RedisScript<Long> REPAIR_SCRIPT = new DefaultRedisScript<>(REPAIR_LUA, Long.class);

    private final StringRedisTemplate redis;

    /** 冷启动重建 / 对账需要读取 billing_event 当日汇总 */
    private final JdbcTemplate jdbc;

    /** 统一配置（预算 key 的 TTL 等），不再散落 @Value */
    private final BidPulseProperties props;


    /**
     * 已预热的广告 + 预热日期（epochDay）。
     *
     * <p>预算 key 按天分桶，同一广告一天只需播种一次。若不记这个集合，
     * 每个 {@code /bid} 都要对全部候选做一轮 {@code SET NX}（P90 会有几百条命令），
     * 纯属把检索侧省下来的写又搬回热路径。
     */
    private final Map<Long, Integer> warmedEpochDay = new ConcurrentHashMap<>();

    /** 本地预热缓存上限：超过后整体清空，防止长跑进程无界增长（100 万条 Long 键约几十 MB） */
    private static final int WARMED_CACHE_MAX = 1_000_000;

    public BudgetService(StringRedisTemplate redis, JdbcTemplate jdbc, BidPulseProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
    }

    private String budgetKey(Long adId, String date) {
        return "budget:" + adId + ":" + date;
    }

    private String spentKey(Long adId, String date) {
        return "spent:" + adId + ":" + date;
    }

    private static List<String> keys(Long adId, LocalDate date) {
        String d = date.format(DATE_FMT);
        return Arrays.asList("budget:" + adId + ":" + d, "spent:" + adId + ":" + d);
    }

    /** 频次键：按「用户 + 自然小时」分窗，Hash field 为 adId。 */
    private static String freqKey(String userId, LocalDateTime time) {
        return "freq:u:" + userId + ":" + time.format(HOUR_FMT);
    }

    private long keyTtlSeconds() {
        return Math.max(1, props.getBudget().getKeyTtlSeconds());
    }

    /**
     * 查询当前余额，不存在则按广告日预算初始化（惰性初始化，播种来自 MySQL）。
     * M3 起检索阶段会批量预热，避免竞价链路上出现初始化写。
     */
    public BigDecimal getBalance(Long adId, BigDecimal dailyBudget) {
        LocalDate date = LocalDate.now();
        String d = date.format(DATE_FMT);
        String v = redis.opsForValue().get(budgetKey(adId, d));
        if (v == null) {
            initIfAbsent(adId, dailyBudget, d);
            v = redis.opsForValue().get(budgetKey(adId, d));
        }
        return parse(v == null ? String.valueOf(toCents(dailyBudget)) : v);
    }

    /**
     * <b>只读</b>批量查询当日剩余预算，不做任何播种（key 不存在时按「今天还没消费」处理）。
     *
     * <p>与 {@link #getBalance} 的区别：{@code getBalance} 在 key 缺失时会写一次
     * {@code SET NX}，那是「初始化」语义；二价定价只是「看一眼竞争者付不付得起」，
     * 不该在热路径上产生写，更不该因为一次竞态把预算 key 提前播种。
     *
     * <p><b>余额口径</b>（不能用「budget key 缺失 = 整笔日预算可用」直接兜底：
     * Redis 重启 / FLUSH / 淘汰 / 预热被 {@code warmedEpochDay} 跳过，都可能让 key 单独消失）：
     * <ul>
     *   <li>{@code budget:*} 存在 → 直接采用它（最权威）；</li>
     *   <li>{@code budget:*} 缺失但 {@code spent:*} 存在 → 用 {@code 日预算 − spent} 反推；</li>
     *   <li>两个 key 都不存在 → 今天还没消费过，额度即日预算。</li>
     * </ul>
     *
     * <p>一次 {@code MGET} 取回全部 key（{@code budget}/{@code spent} 交替排列），
     * 避免 fall-through 时对同一批候选反复 GET；任何读取异常都上抛给调用方，
     * 由它决定「只读查询故障」时如何降级。
     *
     * <p><b>注意</b>：第三条在「双 key 丢失但 DB 有当日账单」时会高估余额。这里刻意不查 DB：
     * 二价只读查询在热路径上，不能为每个候选加一次 DB 往返；真正扣减时 DEDUCT_LUA 会返回
     * {@link DeductResult#NEEDS_SEED}，由 DB 账单重建后再判定。定价误差只影响成交价的估计，
     * 不会导致超扣。
     *
     * @param ads 需要查询的广告（使用 id 与 dailyBudget 字段）
     * @return adId → 当日剩余额度（元）；入参为空时返回空 Map
     */
    public Map<Long, BigDecimal> peekBalances(List<Ad> ads) {
        if (ads == null || ads.isEmpty()) {
            return Map.of();
        }
        String date = LocalDate.now().format(DATE_FMT);
        List<String> keys = new ArrayList<>(ads.size() * 2);
        for (Ad ad : ads) {
            keys.add(budgetKey(ad.getId(), date));
            keys.add(spentKey(ad.getId(), date));
        }
        List<String> values = redis.opsForValue().multiGet(keys);

        Map<Long, BigDecimal> out = new HashMap<>(ads.size());
        for (int i = 0; i < ads.size(); i++) {
            String bal = (values != null && i * 2 < values.size()) ? values.get(i * 2) : null;
            String spent = (values != null && i * 2 + 1 < values.size()) ? values.get(i * 2 + 1) : null;
            Ad ad = ads.get(i);

            BigDecimal remain;
            if (bal != null) {
                remain = parse(bal);
            } else if (spent != null && ad.getDailyBudget() != null) {
                long remainCents;
                try {
                    remainCents = toCents(ad.getDailyBudget()) - Long.parseLong(spent);
                } catch (NumberFormatException e) {
                    // 脏数据：退化为满额，至少不会把脏值当成「已耗尽」
                    remainCents = toCents(ad.getDailyBudget());
                }
                remain = parse(String.valueOf(Math.max(0, remainCents)));
            } else if (ad.getDailyBudget() != null) {
                remain = ad.getDailyBudget();
            } else {
                // 日预算缺失的候选无法计费，按 0 处理（不参与定价）
                remain = BigDecimal.ZERO;
            }
            out.put(ad.getId(), remain);
        }
        return out;
    }

    /**
     * 扣减预算（完整流程：Redis 原子扣减 + 双 key 丢失时按 DB 账单重建后重试）。
     *
     * <p>保留这个布尔入口给测试与简单调用方；竞价主链路用
     * {@link #deductResult} + {@link #seedFromDb} 分两步调用，以便把「Redis 操作」
     * 与「DB 查询」分别放进各自的熔断 / 计时边界，避免 DB 故障被误计为 Redis 故障。
     *
     * <p>不传 {@code userId} 时不做频次限制。
     *
     * @return true 扣减成功；false 余额不足、频次超限或重建后仍无法扣减
     */
    public boolean deduct(Long adId, BigDecimal dailyBudget, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now();
        return deductInternal(adId, dailyBudget, amount, now.toLocalDate(), null, now);
    }

    /** 带用户维度的扣减（竞价主链路使用）：同时受预算与每小时频次限制。 */
    public boolean deduct(Long adId, BigDecimal dailyBudget, BigDecimal amount, String userId) {
        LocalDateTime now = LocalDateTime.now();
        return deductInternal(adId, dailyBudget, amount, now.toLocalDate(), userId, now);
    }

    /** 测试用：固定日期，验证 budget/spent 两个 key 使用同一个日期分桶。 */
    boolean deduct(Long adId, BigDecimal dailyBudget, BigDecimal amount, LocalDate date) {
        return deductInternal(adId, dailyBudget, amount, date, null, date.atStartOfDay());
    }

    /** 测试用：固定时间，验证频次按小时分窗且与扣减保持原子性。 */
    boolean deduct(Long adId, BigDecimal dailyBudget, BigDecimal amount, String userId, LocalDateTime time) {
        return deductInternal(adId, dailyBudget, amount, time.toLocalDate(), userId, time);
    }

    private boolean deductInternal(Long adId, BigDecimal dailyBudget, BigDecimal amount,
                                   LocalDate date, String userId, LocalDateTime time) {
        DeductResult r = deductResult(adId, dailyBudget, amount, date, userId, time);
        if (r == DeductResult.NEEDS_SEED) {
            seedFromDb(adId, dailyBudget, date);
            r = deductResult(adId, dailyBudget, amount, date, userId, time);
        }
        return r == DeductResult.SUCCESS;
    }

    /**
     * 纯 Redis 原子扣减，不访问 DB。
     *
     * @return {@link DeductResult}；双 key 都缺失时返回 {@code NEEDS_SEED}，
     *         调用方应先用 {@link #seedFromDb} 重建后重试一次；
     *         用户本小时对该广告的成交次数已达上限时返回 {@code FREQ_CAPPED}
     * @throws IllegalStateException Redis 未返回可识别的结果（基础设施异常，不能当成余额不足）
     */
    public DeductResult deductResult(Long adId, BigDecimal dailyBudget, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now();
        return deductResult(adId, dailyBudget, amount, now.toLocalDate(), null, now);
    }

    /** 带用户维度的原子扣减（竞价主链路使用）。 */
    public DeductResult deductResult(Long adId, BigDecimal dailyBudget, BigDecimal amount, String userId) {
        LocalDateTime now = LocalDateTime.now();
        return deductResult(adId, dailyBudget, amount, now.toLocalDate(), userId, now);
    }

    DeductResult deductResult(Long adId, BigDecimal dailyBudget, BigDecimal amount, LocalDate date) {
        return deductResult(adId, dailyBudget, amount, date, null, date.atStartOfDay());
    }

    /** 测试用：固定时间，频次键会落到 {@code yyyyMMddHH} 对应的窗口。 */
    DeductResult deductResult(Long adId, BigDecimal dailyBudget, BigDecimal amount,
                              String userId, LocalDateTime time) {
        return deductResult(adId, dailyBudget, amount, time.toLocalDate(), userId, time);
    }

    private DeductResult deductResult(Long adId, BigDecimal dailyBudget, BigDecimal amount,
                                      LocalDate date, String userId, LocalDateTime time) {
        BidPulseProperties.Frequency cfg = props.getFrequency();
        // userId 为空 = 匿名流量：没有稳定的用户维度，直接跳过频次逻辑，
        // 避免把大量匿名请求挤进同一个 "null" 计数器。
        boolean freqEnabled = cfg.isEnabled() && userId != null && !userId.isBlank();
        List<String> keys = new ArrayList<>(3);
        keys.addAll(keys(adId, date));
        // 关闭频次时脚本不会访问 KEYS[3]，但仍需占位保证 KEYS 数量固定（便于脚本静态推断）。
        keys.add(freqKey(freqEnabled ? userId : "__none__", time));

        Long result = redis.execute(DEDUCT_SCRIPT, keys,
                String.valueOf(toCents(amount)),
                String.valueOf(toCents(dailyBudget)),
                String.valueOf(keyTtlSeconds()),
                freqEnabled ? "1" : "0",
                String.valueOf(adId),
                String.valueOf(Math.max(1, cfg.getMaxPerHour())),
                String.valueOf(Math.max(1, cfg.getKeyTtlSeconds())));
        if (result == null) {
            // 绝不能把「Redis 没返回」当成余额不足：调用方会据此把广告标成已耗尽。
            throw new IllegalStateException("Redis 预算扣减脚本无返回");
        }
        if (result == 1L) {
            return DeductResult.SUCCESS;
        }
        if (result == 0L) {
            return DeductResult.INSUFFICIENT;
        }
        if (result == -1L) {
            return DeductResult.NEEDS_SEED;
        }
        if (result == 2L) {
            return DeductResult.FREQ_CAPPED;
        }
        throw new IllegalStateException("未知的预算扣减返回码: " + result);
    }

    /**
     * 用 DB 当日账单重建 budget / spent（双 key 丢失的冷启动路径）。
     *
     * <p>只应在 {@link #deductResult} 返回 {@code NEEDS_SEED} 时调用。查询
     * {@code billing_event} 当日 {@code SUM(pay_price)} 作为已消耗，两个 key 用
     * {@code SET NX} 写入，不会覆盖并发请求已经建立的状态。
     *
     * <p>DB 查询失败时会向上抛出：调用方应放弃本次竞价（走兜底），
     * 而不是按「整笔日预算」放款造成超花。
     *
     * @return 重建后（或并发请求已写入）的剩余额度（元）；Redis 未返回时为 null
     */
    public BigDecimal seedFromDb(Long adId, BigDecimal dailyBudget) {
        return seedFromDb(adId, dailyBudget, LocalDate.now());
    }

    BigDecimal seedFromDb(Long adId, BigDecimal dailyBudget, LocalDate date) {
        long dbSpent = spentCentsFromDb(adId, date);
        Long remainCents = redis.execute(SEED_SCRIPT, keys(adId, date),
                String.valueOf(toCents(dailyBudget)),
                String.valueOf(dbSpent),
                String.valueOf(keyTtlSeconds()));
        if (remainCents == null) {
            log.warn("预算冷启动播种无返回 adId={} date={}", adId, date);
            return null;
        }
        log.info("预算冷启动播种 adId={} date={}：DB 已消耗 {} 分，播种后剩余 {} 分",
                adId, date, dbSpent, remainCents);
        return parse(String.valueOf(remainCents));
    }

    /**
     * 对账修复：Redis spent 低于 DB 账单时按账单重算（只升不降）。
     *
     * @param dbSpentCents 已由对账任务批量查询好的 DB 当日消耗（分）
     * @return true = 本次执行了修复；false = 当前值已不低于账单，无需修复
     */
    public boolean repairSpentFromDb(Long adId, BigDecimal dailyBudget, long dbSpentCents, LocalDate date) {
        Long result = redis.execute(REPAIR_SCRIPT, keys(adId, date),
                String.valueOf(toCents(dailyBudget)),
                String.valueOf(Math.max(0, dbSpentCents)),
                String.valueOf(keyTtlSeconds()));
        return result != null && result == 1L;
    }

    /**
     * 从 billing_event 汇总某广告某日的实际扣费（分）。
     *
     * <p>显式 {@code setQueryTimeout(1)}：这条查询在竞价线程上执行（双 key 丢失的冷启动路径），
     * 不能让一次慢 SQL 把整条请求拖过 SLA。JdbcTemplate 默认无语句超时，
     * 只有数据源 {@code socketTimeout=3000} 兜底——那是 3 秒，远超竞价总预算。
     */
    private long spentCentsFromDb(Long adId, LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String sql = "SELECT COALESCE(SUM(pay_price), 0) FROM billing_event "
                + "WHERE ad_id = ? AND ts >= ? AND ts < ?";
        BigDecimal sum = jdbc.execute((ConnectionCallback<BigDecimal>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setQueryTimeout(1);
                ps.setLong(1, adId);
                ps.setLong(2, start);
                ps.setLong(3, end);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
                }
            }
        });
        return toCents(sum == null ? BigDecimal.ZERO : sum);
    }

    /**
     * 把广告「当天」的预算调整为新的日预算（管理端 {@code PUT /admin/ad/{id}} 修改 daily_budget 时调用）。
     *
     * <p><b>为什么需要</b>：预热（{@code SET NX}）与 Lua 惰性播种都只在 key 缺失时使用传入的
     * {@code dailyBudget}；key 一旦存在，后续传入的新日预算会被完全忽略。结果是管理端改了预算
     * 当天不生效、次日新 key 播种才生效。这里显式重算一次当天 key。
     *
     * <p>余额 = max(0, 新日预算 − 今日已消耗)，避免把已消耗额度重新放出；
     * 新预算低于已消耗时余额归零，广告会在下一次扣减时被判定为耗尽。
     * {@code budget} 与 {@code spent} 用同一个 TTL 写入，避免两者过期时间分叉。
     *
     * <p>失败由调用方记录日志（best-effort）：DB 已是新值而 Redis 仍是旧值，
     * 再次提交或次日新 key 会自然恢复，不应因此回滚已经提交的 DB 修改。
     *
     * @return 重算后的当日剩余额度（元）；Redis 未返回时返回 null
     */
    public BigDecimal applyDailyBudget(Long adId, BigDecimal dailyBudget) {
        return applyDailyBudget(adId, dailyBudget, LocalDate.now());
    }

    BigDecimal applyDailyBudget(Long adId, BigDecimal dailyBudget, LocalDate date) {
        Long remainCents = redis.execute(SET_BUDGET_SCRIPT, keys(adId, date),
                String.valueOf(toCents(dailyBudget)),
                String.valueOf(keyTtlSeconds()));
        return remainCents == null ? null : parse(String.valueOf(remainCents));
    }

    /**
     * 按新日预算重算当天剩余额度（管理端修改 daily_budget 时使用）。
     * KEYS[1] = budget:{adId}:{date} 剩余预算(分)
     * KEYS[2] = spent:{adId}:{date}  累计消耗(分)
     * ARGV[1] = 新日预算(分)
     * ARGV[2] = key TTL(秒)
     * 返回：重算后的剩余额度(分)
     *
     * <p>用「新日预算 − 今日已消耗」而不是直接写入新日预算，否则会把当天已经花掉的钱重新放出来。
     * 读取 spent 与写入 budget 必须在一个 Lua 内原子完成，否则会与并发扣减形成
     * 「读旧值 → 覆盖写」，把期间发生的一次扣减直接抹掉。
     */
    private static final String SET_BUDGET_LUA =
            "local spent = tonumber(redis.call('GET', KEYS[2]))\n" +
            "if not spent then spent = 0 end\n" +
            "spent = math.floor(spent)\n" +
            "if spent < 0 then spent = 0 end\n" +
            "local remain = tonumber(ARGV[1]) - spent\n" +
            "if remain < 0 then remain = 0 end\n" +
            "local ttl = tonumber(ARGV[2])\n" +
            "redis.call('SET', KEYS[1], remain, 'EX', ttl)\n" +
            // 两个 key 用同一个 TTL，避免 budget 比 spent 活得久、或反过来；
            // 顺手把 spent 写成归一化后的整数，覆盖缺失 / 脏值两种情况。
            "redis.call('SET', KEYS[2], spent, 'EX', ttl)\n" +
            "return remain\n";

    private static final RedisScript<Long> SET_BUDGET_SCRIPT =
            new DefaultRedisScript<>(SET_BUDGET_LUA, Long.class);

    /**
     * 检索阶段批量预热：为本次候选惰性补建 Redis 预算 key，把「初始化写」从计费热路径
     * （Lua {@code deduct} 里的播种）提前挪到检索侧。
     *
     * <p><b>本地已预热集合</b>：同一个广告一天只需播种一次，先用
     * {@link #warmedEpochDay} 挡掉重复，避免每个请求都对同一批候选重发一轮
     * {@code SET NX}。集合按 epochDay 记录，跨天自然失效。
     *
     * <p><b>标记时机</b>：只有 pipeline 真正执行成功后才把广告记为「今天已预热」。
     * 若先标记后执行，一次 Redis 抖动会让这批广告当天再也不尝试预热——正确性靠 Lua
     * 惰性播种兜底，但这个优化会静默失效且状态具有误导性。
     *
     * <p>用 {@code SET NX}（{@code setIfAbsent}）——<b>只在 key 不存在时播种</b>，绝不覆盖当天
     * 已消耗的预算；同时带上 {@code bidpulse.budget.key-ttl-seconds} 过期，与 {@code BudgetKeyCleaner} 的定时清理
     * 互为兜底（一个被动过期、一个主动扫尾）。
     *
     * <p>{@code spent} 无需在此播种：判断只看 {@code budget} key；{@code spent} 在首次扣减时
     * 由 Lua 创建，并同样带上 {@code key-ttl-seconds}（见 {@code DEDUCT_LUA}），
     * 因此不会出现「预热过的广告留下永久 spent key」。
     *
     * <p>失败可容忍：预热失败时计费阶段的 Lua 仍会惰性播种（同样带 TTL），不影响正确性，
     * 故由调用方包 try-catch。
     */
    public void warmBudgets(List<Ad> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int today = (int) LocalDate.now().toEpochDay();

        if (warmedEpochDay.size() > WARMED_CACHE_MAX) {
            warmedEpochDay.clear();
        }

        List<Ad> toWarm = new ArrayList<>(candidates.size());
        for (Ad ad : candidates) {
            if (ad == null || ad.getId() == null || ad.getDailyBudget() == null) {
                continue;   // 防御：字段缺失的候选跳过，交给计费时惰性兜底
            }
            // 只读不写：今天已经预热过就跳过；是否记为已预热，等 pipeline 成功后再决定
            Integer warmedDay = warmedEpochDay.get(ad.getId());
            if (!Integer.valueOf(today).equals(warmedDay)) {
                toWarm.add(ad);
            }
        }
        if (toWarm.isEmpty()) {
            return;
        }

        String date = LocalDate.now().format(DATE_FMT);
        Duration ttl = Duration.ofSeconds(keyTtlSeconds());
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                ValueOperations<String, String> ops = operations.opsForValue();
                for (Ad ad : toWarm) {
                    ops.setIfAbsent(budgetKey(ad.getId(), date),
                            String.valueOf(toCents(ad.getDailyBudget())), ttl);
                }
                return null;
            }
        });

        // pipeline 成功后才记账：失败时保持未标记，下一次请求会重试预热
        for (Ad ad : toWarm) {
            warmedEpochDay.put(ad.getId(), today);
        }
    }

    /**
     * 金额统一以「分」为单位存进 Redis，避免 double 的浮点误差。
     *
     * <p>必须显式取整：{@link BigDecimal#longValue()} 会直接截断小数部分，
     * 而 {@code ad.bid} 是 DECIMAL(10,4)、二价成交价可能带 4 位小数，
     * 截断会造成最多 1 分的少收，且与 {@code billing_event.pay_price} 对不上账。
     * 这里统一向下取整（对广告主不超额收费）；调用方已把成交价量化到分，取整不会再丢精度。
     */
    private static long toCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /** 分 -> 元 */
    private static BigDecimal parse(String cents) {
        return new BigDecimal(cents).movePointLeft(2);
    }

    /** 与预热 / Lua 惰性播种同一 TTL 口径，避免产生永不过期的预算 key */
    private void initIfAbsent(Long adId, BigDecimal dailyBudget, String date) {
        redis.opsForValue().setIfAbsent(budgetKey(adId, date),
                String.valueOf(toCents(dailyBudget)),
                Duration.ofSeconds(keyTtlSeconds()));
    }
}
