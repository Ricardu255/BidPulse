package com.bidpulse.infra.redis;

import lombok.extern.slf4j.Slf4j;
import com.bidpulse.config.BidPulseProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bidpulse.entity.Ad;

import static com.bidpulse.infra.redis.BudgetAmounts.parse;
import static com.bidpulse.infra.redis.BudgetAmounts.toCents;
import static com.bidpulse.infra.redis.BudgetKeys.freqKey;
import static com.bidpulse.infra.redis.BudgetKeys.keys;
import static com.bidpulse.infra.redis.BudgetLuaScripts.DEDUCT_SCRIPT;
import static com.bidpulse.infra.redis.BudgetLuaScripts.REPAIR_SCRIPT;
import static com.bidpulse.infra.redis.BudgetLuaScripts.SEED_SCRIPT;
import static com.bidpulse.infra.redis.BudgetLuaScripts.SET_BUDGET_SCRIPT;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>实现拆分</b>：Lua 脚本在 {@link BudgetLuaScripts}，key 命名在 {@link BudgetKeys}，
 * 金额换算是 {@link BudgetAmounts}，只读查询/快照在 {@link BudgetReader}，预热在
 * {@link BudgetWarmup}。本类只保留扣减、播种、对账修复与日预算重算的编排。
 */
@Slf4j
@Service
public class BudgetService {

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
     * 预算只读快照，供控制台展示；不触发播种，也不改变任何状态。
     *
     * @param remainingCents 剩余额度（分）；key 未初始化时为 null
     * @param spentCents     已消耗（分）；key 未初始化时为 null
     * @param ttlSeconds     预算 key 的剩余 TTL（秒）；-1 表示无过期，-2 表示 key 不存在
     * @param seeded         budget key 是否存在
     */
    public record BudgetSnapshot(Long remainingCents, Long spentCents, Long ttlSeconds, boolean seeded) {
    }

    /**
     * 频次只读快照，供控制台展示。
     *
     * @param count      当前小时该用户对该广告的成交次数；尚未计数时为 null
     * @param maxPerHour 配置的上限
     * @param ttlSeconds 频次 key 的剩余 TTL（秒）；-1 无过期，-2 不存在
     */
    public record FrequencySnapshot(Long count, int maxPerHour, Long ttlSeconds) {
    }

    private final StringRedisTemplate redis;

    /** 冷启动重建 / 对账需要读取 billing_event 当日汇总 */
    private final JdbcTemplate jdbc;

    /** 统一配置（预算 key 的 TTL 等），不再散落 @Value */
    private final BidPulseProperties props;

    /** 只读快照 / 余额查询（控制台与二价读取路径） */
    private final BudgetReader reader;

    /** 检索阶段批量预热（本地按天去重） */
    private final BudgetWarmup warmup;

    public BudgetService(StringRedisTemplate redis, JdbcTemplate jdbc, BidPulseProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
        this.reader = new BudgetReader(redis, props);
        this.warmup = new BudgetWarmup(redis, props);
    }

    /** 读取某广告当天的预算状态；只读，不触发播种。 */
    public BudgetSnapshot snapshot(Long adId, BigDecimal dailyBudget) {
        return reader.snapshot(adId, dailyBudget);
    }

    /** 读取某用户当前小时对某广告的频次计数；只读。 */
    public FrequencySnapshot snapshotFrequency(String userId, Long adId) {
        return reader.snapshotFrequency(userId, adId);
    }

    /** 查询当前余额，不存在则按广告日预算初始化（惰性播种）。 */
    public BigDecimal getBalance(Long adId, BigDecimal dailyBudget) {
        return reader.getBalance(adId, dailyBudget);
    }

    /** 只读批量查询余额，不播种；供二价 fall-through 使用。 */
    public Map<Long, BigDecimal> peekBalances(List<Ad> ads) {
        return reader.peekBalances(ads);
    }

    /** 检索阶段批量预热；具体去重与 pipeline 在 {@link BudgetWarmup}。 */
    public void warmBudgets(List<Ad> candidates) {
        warmup.warm(candidates);
    }

    private long keyTtlSeconds() {
        return Math.max(1, props.getBudget().getKeyTtlSeconds());
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


}
