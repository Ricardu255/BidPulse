package com.bidpulse.infra.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 预算/频次相关的 Lua 脚本注册表。
 *
 * <p>脚本集中在这里，{@link BudgetService} 只负责编排与结果映射。
 * 脚本的 KEYS/ARGV 约定见每条常量上的注释；改动脚本必须同步跑
 * {@code BudgetServiceTest} 的集成用例。
 */
final class BudgetLuaScripts {

    private BudgetLuaScripts() {
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

    static final RedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>(DEDUCT_LUA, Long.class);

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

    static final RedisScript<Long> SEED_SCRIPT = new DefaultRedisScript<>(SEED_LUA, Long.class);

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

    static final RedisScript<Long> REPAIR_SCRIPT = new DefaultRedisScript<>(REPAIR_LUA, Long.class);

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

    static final RedisScript<Long> SET_BUDGET_SCRIPT =
            new DefaultRedisScript<>(SET_BUDGET_LUA, Long.class);

}
