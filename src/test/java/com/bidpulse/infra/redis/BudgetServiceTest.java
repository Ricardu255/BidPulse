package com.bidpulse.infra.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.bidpulse.entity.Ad;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.infra.buffer.ExhaustedBuffer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BudgetService 预算扣减单元测试。
 *
 * <p>覆盖：
 * ① 预算充足 -> 返回 true 且余额按分精确扣减；
 * ② 余额不足 -> 返回 false，状态回写交给 ExhaustedBuffer；
 * ③ 管理端改日预算 -> 按「新预算 − 今日已消耗」重算，budget/spent 的 TTL 对齐；
 * ④ budget key 单独丢失 -> 按「日预算 − spent」重播种，不重新放出已花的钱；
 * ⑤ {@code peekBalances} -> 只读、批量、缺 budget 时用 spent 反推；
 * ⑥ spent 为非整数脏值 -> 归一化为 0 并覆盖写回，扣减不报错；
 * ⑦ budget/spent 双 key 丢失 -> 返回 NEEDS_SEED，用 billing_event 当日汇总重建后扣减；
 * ⑧ 扣减与改预算的两个 key 必须落在同一日期分桶（跨零点不能分裂）；
 * ⑨ 实时频次控制 -> 同一用户对同一广告每小时最多成交 N 次，超限不扣预算、不计数、
 *    不影响其它用户 / 其它广告，且跨小时自然重置。
 *
 * <p>真连 Kafka：上下文含 Kafka 生产者/消费者与 {@code KafkaAdmin}（建 topic），
 * 故需 Kafka broker 可用（本地 docker-compose / CI services）。
 */
@SpringBootTest
@Tag("integration")
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class BudgetServiceTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ExhaustedBuffer exhaustedBuffer;

    @Autowired
    private BidPulseProperties props;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** 跨天用例使用的固定日期，避免依赖「测试跑在几点」 */
    private static final LocalDate FIXED_DATE = LocalDate.of(2031, 2, 3);
    private static final String FIXED_SUFFIX = FIXED_DATE.format(FMT);

    /** 频次用例使用的固定时刻：2031-02-03 10:15，窗口键为 ...2031020310 */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2031, 2, 3, 10, 15);
    private static final String FIXED_HOUR_SUFFIX = FIXED_TIME.format(HOUR_FMT);

    /** 频次用例的用户前缀：清理时按前缀扫，不依赖测试跑在哪一个小时 */
    private static final String FREQ_USER = "test-freq-user-a";
    private static final String FREQ_USER_B = "test-freq-user-b";

    /** 每次运行前复位状态 + 清 Redis 预算/频次 key，保证幂等 */
    @BeforeEach
    void reset() {
        jdbcTemplate.update("UPDATE ad SET status = 1 WHERE id IN (900010, 900011)");
        String d = LocalDate.now().format(FMT);
        redis.delete(List.of(
                "budget:900010:" + d, "spent:900010:" + d,
                "budget:900011:" + d, "spent:900011:" + d,
                "budget:900010:" + FIXED_SUFFIX, "spent:900010:" + FIXED_SUFFIX,
                "budget:900011:" + FIXED_SUFFIX, "spent:900011:" + FIXED_SUFFIX));
        cleanFreqKeys();
    }

    @AfterEach
    void cleanRedis() {
        String d = LocalDate.now().format(FMT);
        redis.delete(List.of(
                "budget:900010:" + d, "spent:900010:" + d,
                "budget:900011:" + d, "spent:900011:" + d,
                "budget:900010:" + FIXED_SUFFIX, "spent:900010:" + FIXED_SUFFIX,
                "budget:900011:" + FIXED_SUFFIX, "spent:900011:" + FIXED_SUFFIX));
        cleanFreqKeys();
    }

    /** 只删测试用户前缀的频次键，不碰其它用例（例如 BidFlowTest）的键。 */
    private void cleanFreqKeys() {
        var keys = redis.keys("freq:u:test-freq-*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 频次用例直接调 {@code deductResult}（不经 NEEDS_SEED 自动重建），先把预算 key 播种好。 */
    private void seedBudget(Long adId, String dailyBudget) {
        budgetService.applyDailyBudget(adId, new BigDecimal(dailyBudget), FIXED_DATE);
    }

    @Test
    void deduct_succeedsWhenBudgetSufficient() {
        boolean ok = budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("0.10"));
        assertTrue(ok, "预算充足应扣减成功");

        BigDecimal balance = budgetService.getBalance(900010L, new BigDecimal("100.00"));
        assertEquals(0, balance.compareTo(new BigDecimal("99.90")), "扣减 0.10 后应剩 99.90 元");
    }

    /**
     * M11：检索阶段预热只 {@code SET NX} 了 budget key，spent 由首次扣减创建。
     * 这条用例锁住「spent 必须带 TTL」——否则预热过的广告每按一次扣减就留下一个永久 key，
     * Redis 只增不减（本仓库 noeviction），最终写命令被拒、预算扣减全线失败。
     */
    @Test
    void deduct_createsSpentKeyWithTtl_whenOnlyBudgetWasWarmed() {
        String d = LocalDate.now().format(FMT);
        String budgetKey = "budget:900010:" + d;
        String spentKey = "spent:900010:" + d;

        // 只播种 budget（模拟 BudgetService.warmBudgets 的 SET NX + TTL），spent 尚不存在
        redis.opsForValue().set(budgetKey, "10000", Duration.ofSeconds(60));
        assertFalse(redis.hasKey(spentKey), "前置条件：spent 不应存在");

        assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "预算充足应扣减成功");

        Long ttl = redis.getExpire(spentKey, TimeUnit.SECONDS);
        assertNotNull(ttl, "扣减后 spent key 应存在");
        assertTrue(ttl > 0, "spent key 必须带 TTL，实际=" + ttl);
        assertEquals(0, new BigDecimal("99.90").compareTo(
                        budgetService.getBalance(900010L, new BigDecimal("100.00"))),
                "余额应为 99.90 元");
    }

    /**
     * budget key 单独丢失（Redis 重启 / FLUSH / 淘汰）时，必须用「日预算 − spent」重新播种，
     * 不能直接放满整笔日预算，否则当天已经花掉的钱会被重新放出来。
     */
    @Test
    void deduct_reseedsFromDailyBudgetMinusSpent_whenBudgetKeyLost() {
        String d = LocalDate.now().format(FMT);
        String budgetKey = "budget:900010:" + d;
        String spentKey = "spent:900010:" + d;

        // 当天已消耗 10 元，budget key 丢失
        redis.opsForValue().set(spentKey, "1000", Duration.ofSeconds(60));
        assertFalse(redis.hasKey(budgetKey), "前置条件：budget key 不应存在");

        assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "按 日预算-spent 重播后余额 90 元，扣 0.10 应成功");
        assertEquals(0, new BigDecimal("89.90").compareTo(
                        budgetService.getBalance(900010L, new BigDecimal("100.00"))),
                "重播余额应为 90 - 0.10 = 89.90 元，而不是 100 - 0.10");
    }

    /**
     * spent 是非整数脏值（人工改 key / 迁移残留）时，扣减不能报错：
     * Lua 脚本出错不会回滚已经执行的 {@code DECRBY}，会留下「预算已扣、消耗没记」的账。
     */
    @Test
    void deduct_normalizesDirtySpentValue() {
        String d = LocalDate.now().format(FMT);
        String spentKey = "spent:900010:" + d;

        redis.opsForValue().set(spentKey, "abc", Duration.ofSeconds(600));

        assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "脏 spent 应按 0 处理，扣减成功");
        assertEquals("10", redis.opsForValue().get(spentKey),
                "spent 应被覆盖为本次扣减额（分）");
        assertEquals(0, new BigDecimal("99.90").compareTo(
                        budgetService.getBalance(900010L, new BigDecimal("100.00"))),
                "余额应为 100 - 0.10 = 99.90 元");
    }

    /**
     * {@code applyDailyBudget} 让 budget 与 spent 用同一个 TTL，避免两者过期时间分叉。
     */
    @Test
    void applyDailyBudget_alignsSpentTtlWithBudget() {
        String d = LocalDate.now().format(FMT);
        String budgetKey = "budget:900010:" + d;
        String spentKey = "spent:900010:" + d;

        redis.opsForValue().set(spentKey, "1000", Duration.ofSeconds(600));
        budgetService.applyDailyBudget(900010L, new BigDecimal("50.00"));

        Long budgetTtl = redis.getExpire(budgetKey, TimeUnit.SECONDS);
        Long spentTtl = redis.getExpire(spentKey, TimeUnit.SECONDS);
        assertNotNull(budgetTtl);
        assertNotNull(spentTtl);
        assertTrue(Math.abs(budgetTtl - spentTtl) <= 2,
                "budget 与 spent 的 TTL 应对齐，实际 budget=" + budgetTtl + ", spent=" + spentTtl);
    }

    /**
     * 只读批量查询：budget key 存在时用它；缺失但 spent 存在时用 日预算 − spent；
     * 且绝不能在热路径上播种（写 Redis）。
     */
    @Test
    void peekBalances_isReadOnly_andFallsBackToSpent() {
        String d = LocalDate.now().format(FMT);
        String budgetKey900010 = "budget:900010:" + d;
        String budgetKey900011 = "budget:900011:" + d;

        redis.opsForValue().set(budgetKey900010, "1234");
        redis.opsForValue().set("spent:900011:" + d, "500");

        Ad a = new Ad();
        a.setId(900010L);
        a.setDailyBudget(new BigDecimal("100.00"));
        Ad b = new Ad();
        b.setId(900011L);
        b.setDailyBudget(new BigDecimal("100.00"));

        Map<Long, BigDecimal> balances = budgetService.peekBalances(List.of(a, b));

        assertEquals(0, new BigDecimal("12.34").compareTo(balances.get(900010L)),
                "budget key 存在时应直接返回剩余额度");
        assertEquals(0, new BigDecimal("95.00").compareTo(balances.get(900011L)),
                "budget 缺失、spent=5 元时余额应为 100 - 5 = 95 元");
        assertFalse(redis.hasKey(budgetKey900011), "peekBalances 不应播种 budget key");
    }

    @Test
    void deduct_failsWhenBudgetInsufficient_andBufferMarksExhausted() throws InterruptedException {
        boolean ok = budgetService.deduct(900011L, new BigDecimal("0.01"), new BigDecimal("5.00"));
        assertFalse(ok, "余额不足应扣减失败");

        // M7 起 deduct 只做 Redis 原子扣减，不再顺带写 MySQL：
        // 状态回写由 BidService 投递 ExhaustedBuffer、后台线程攒批完成。
        Integer before = jdbcTemplate.queryForObject(
                "SELECT status FROM ad WHERE id = 900011", Integer.class);
        assertEquals(1, before, "Redis 扣减层不应直接修改 MySQL 状态");

        exhaustedBuffer.enqueue(900011L);
        long deadline = System.currentTimeMillis() + 5_000;
        Integer status = before;
        while (System.currentTimeMillis() < deadline && (status == null || status != 3)) {
            Thread.sleep(50);
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM ad WHERE id = 900011", Integer.class);
        }
        assertEquals(3, status, "ExhaustedBuffer 应在后台把状态回写为 3");
    }

    /**
     * 管理端改日预算：当天额度按「新日预算 − 今日已消耗」重算，而不是把新预算原样写回
     * （否则已花掉的钱会被重新放出来）；新预算低于已消耗时余额归零。
     */
    @Test
    void applyDailyBudget_recomputesRemainingAgainstSpent() {
        assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("10.00")),
                "先消耗 10 元，作为今日已花费");

        BigDecimal remain = budgetService.applyDailyBudget(900010L, new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("40.00").compareTo(remain),
                "新日预算 50 元、今日已消耗 10 元 → 剩余应为 40 元");
        assertEquals(0, new BigDecimal("40.00").compareTo(
                        budgetService.getBalance(900010L, new BigDecimal("50.00"))),
                "Redis 里的余额应与重算结果一致");

        BigDecimal floored = budgetService.applyDailyBudget(900010L, new BigDecimal("5.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(floored),
                "新预算低于今日已消耗时余额应归零，而不是变成负数");
    }

    /**
     * <b>P0 双 key 丢失</b>：预算与消耗 key 同时消失（Redis 无持久化重启 / FLUSH）时，
     * 绝不能按「整笔日预算」放款——必须先用 billing_event 当日汇总重建，再重试扣减。
     */
    @Test
    void deduct_seedsFromBillingEvent_whenBothKeysMissing() {
        String d = LocalDate.now().format(FMT);
        String budgetKey = "budget:900010:" + d;
        String spentKey = "spent:900010:" + d;

        // 今天已经通过竞价扣过 30 元，账单已落库；随后两个预算 key 都丢失
        jdbcTemplate.update(
                "INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts) "
                        + "VALUES (?,?,?,?,?)",
                "seed-both-missing-900010", 900010L, 900010, new BigDecimal("30.00"),
                System.currentTimeMillis());

        // 纯 Redis 扣减无法判定当日已消耗 → 返回 NEEDS_SEED，且不写任何 key
        assertEquals(BudgetService.DeductResult.NEEDS_SEED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "双 key 都缺失时应返回 NEEDS_SEED");
        assertFalse(redis.hasKey(budgetKey), "NEEDS_SEED 阶段不应写入 budget key");
        assertFalse(redis.hasKey(spentKey), "NEEDS_SEED 阶段不应写入 spent key");

        // 用 DB 账单重建：剩余 = 100 - 30 = 70
        BigDecimal remain = budgetService.seedFromDb(900010L, new BigDecimal("100.00"));
        assertEquals(0, new BigDecimal("70.00").compareTo(remain), "播种后剩余应为 70 元");
        assertEquals("3000", redis.opsForValue().get(spentKey), "spent 应回填为账单汇总（分）");

        // 重建后重试扣减成功，spent 在账单基础上累加
        assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "重建后余额充足，应扣减成功");
        assertEquals("3010", redis.opsForValue().get(spentKey), "spent 应为 3000 + 10 分");
        assertEquals(0, new BigDecimal("69.90").compareTo(
                        budgetService.getBalance(900010L, new BigDecimal("100.00"))),
                "剩余应为 70 - 0.10 = 69.90 元");
    }

    /**
     * 账单汇总超过日预算时，重建后的余额必须钳到 0，而不是负数；
     * 后续扣减返回 INSUFFICIENT（余额不足），不能再次触发 NEEDS_SEED 形成死循环。
     */
    @Test
    void seedFromDb_clampsRemainingToZero_whenDbSpendExceedsDailyBudget() {
        String d = LocalDate.now().format(FMT);
        String spentKey = "spent:900010:" + d;

        jdbcTemplate.update(
                "INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts) "
                        + "VALUES (?,?,?,?,?)",
                "seed-over-budget-900010", 900010L, 900010, new BigDecimal("150.00"),
                System.currentTimeMillis());

        BigDecimal remain = budgetService.seedFromDb(900010L, new BigDecimal("100.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(remain), "账单超过日预算时剩余应为 0");
        assertEquals("15000", redis.opsForValue().get(spentKey));

        assertEquals(BudgetService.DeductResult.INSUFFICIENT,
                budgetService.deductResult(900010L, new BigDecimal("100.00"), new BigDecimal("0.10")),
                "重建后应正常判为余额不足，而不是再次 NEEDS_SEED");
    }

    /**
     * <b>P0 跨零点</b>：扣减必须用「同一个」日期生成 budget 与 spent 两个 key。
     * 早先两次独立调用 {@code LocalDate.now()}，恰好在零点跨天时会把 budget 写到新的一天、
     * 把 spent 写到旧的一天，两个 key 互相矛盾。
     *
     * <p>固定日期重载（包级可见）让这个不变量可被测试：两个 key 必须都落 {@code 20310203}。
     */
    @Test
    void deduct_usesSameDateForBudgetAndSpentKeys() {
        assertTrue(budgetService.deduct(
                        900010L, new BigDecimal("100.00"), new BigDecimal("0.10"), FIXED_DATE),
                "固定日期扣减应成功");

        assertEquals("9990", redis.opsForValue().get("budget:900010:" + FIXED_SUFFIX),
                "budget key 应落在传入日期分桶");
        assertEquals("10", redis.opsForValue().get("spent:900010:" + FIXED_SUFFIX),
                "spent key 应与 budget 使用同一日期分桶");
        String today = LocalDate.now().format(FMT);
        assertFalse(redis.hasKey("budget:900010:" + today), "不得写今天的 budget key");
        assertFalse(redis.hasKey("spent:900010:" + today), "不得写今天的 spent key");
    }

    /** 管理端改日预算同样必须两个 key 同日期分桶。 */
    @Test
    void applyDailyBudget_usesSameDateForBudgetAndSpentKeys() {
        BigDecimal remain = budgetService.applyDailyBudget(
                900010L, new BigDecimal("50.00"), FIXED_DATE);

        assertEquals(0, new BigDecimal("50.00").compareTo(remain), "当日无消耗，剩余即 50 元");
        assertEquals("5000", redis.opsForValue().get("budget:900010:" + FIXED_SUFFIX));
        assertEquals("0", redis.opsForValue().get("spent:900010:" + FIXED_SUFFIX),
                "spent 也应写入同一日期分桶（0 表示今日未消耗）");
    }

    // =================================================================
    // P1 实时频次控制
    // =================================================================

    /**
     * 同一用户对同一广告达到每小时上限后返回 {@code FREQ_CAPPED}；
     * 被频次拦下的那次<b>不能</b>扣预算，也不能再占用一次频次额度。
     */
    @Test
    void frequencyCap_blocksAfterLimit_andCappedRequestDoesNotDeduct() {
        seedBudget(900010L, "100.00");
        int limit = props.getFrequency().getMaxPerHour();
        String freqKey = "freq:u:" + FREQ_USER + ":" + FIXED_HOUR_SUFFIX;

        for (int i = 1; i <= limit; i++) {
            assertEquals(BudgetService.DeductResult.SUCCESS,
                    budgetService.deductResult(900010L, new BigDecimal("100.00"),
                            new BigDecimal("0.10"), FREQ_USER, FIXED_TIME),
                    "第 " + i + " 次成交应成功");
        }
        assertEquals(String.valueOf(limit * 10),
                redis.opsForValue().get("spent:900010:" + FIXED_SUFFIX),
                "成功 " + limit + " 次，每次 0.10 元，spent 应为 " + (limit * 10) + " 分");
        assertEquals(String.valueOf(limit), redis.opsForHash().get(freqKey, "900010"),
                "频次 Hash 的 field=adId，计数应等于成交次数");

        Long ttl = redis.getExpire(freqKey, TimeUnit.SECONDS);
        assertNotNull(ttl, "频次 key 必须存在");
        assertTrue(ttl > 0 && ttl <= props.getFrequency().getKeyTtlSeconds(),
                "频次 key 应带配置的 TTL，实际=" + ttl);

        assertEquals(BudgetService.DeductResult.FREQ_CAPPED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME),
                "超过每小时上限应返回 FREQ_CAPPED");

        // 被拦下的这次既不扣钱、也不把计数推到 limit+1
        assertEquals(String.valueOf(limit * 10),
                redis.opsForValue().get("spent:900010:" + FIXED_SUFFIX),
                "频次超限不能扣预算");
        assertEquals(String.valueOf(limit), redis.opsForHash().get(freqKey, "900010"),
                "频次超限不能再累加计数");
    }

    /** 频次按 userId 隔离：一个用户刷满上限不能影响另一个用户。 */
    @Test
    void frequencyCap_isPerUser() {
        seedBudget(900010L, "100.00");
        int limit = props.getFrequency().getMaxPerHour();
        for (int i = 0; i < limit; i++) {
            budgetService.deductResult(900010L, new BigDecimal("100.00"),
                    new BigDecimal("0.10"), FREQ_USER, FIXED_TIME);
        }
        assertEquals(BudgetService.DeductResult.FREQ_CAPPED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME));

        assertEquals(BudgetService.DeductResult.SUCCESS,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER_B, FIXED_TIME),
                "另一个用户应能正常成交");
        assertEquals("1", redis.opsForHash().get(
                        "freq:u:" + FREQ_USER_B + ":" + FIXED_HOUR_SUFFIX, "900010"),
                "频次计数按用户分开");
    }

    /** 频次按 adId 隔离：同一用户被广告 A 限流，不影响广告 B。 */
    @Test
    void frequencyCap_isPerAd() {
        seedBudget(900010L, "100.00");
        seedBudget(900011L, "100.00");
        int limit = props.getFrequency().getMaxPerHour();
        for (int i = 0; i < limit; i++) {
            budgetService.deductResult(900010L, new BigDecimal("100.00"),
                    new BigDecimal("0.10"), FREQ_USER, FIXED_TIME);
        }
        assertEquals(BudgetService.DeductResult.FREQ_CAPPED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME));

        assertTrue(budgetService.deduct(900011L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME),
                "另一个广告不应受影响");
        assertEquals("1", redis.opsForHash().get(
                        "freq:u:" + FREQ_USER + ":" + FIXED_HOUR_SUFFIX, "900011"),
                "两个广告在同一用户 Hash 中是不同的 field");
    }

    /** 余额不足不占用频次额度：不能因为一次没钱就提前把用户限流。 */
    @Test
    void frequencyCap_doesNotCountInsufficientAttempts() {
        budgetService.applyDailyBudget(900011L, new BigDecimal("0.01"), FIXED_DATE);

        for (int i = 0; i < 4; i++) {
            assertEquals(BudgetService.DeductResult.INSUFFICIENT,
                    budgetService.deductResult(900011L, new BigDecimal("0.01"),
                            new BigDecimal("0.10"), FREQ_USER, FIXED_TIME),
                    "余额不足应返回 INSUFFICIENT");
        }
        assertNull(redis.opsForHash().get(
                        "freq:u:" + FREQ_USER + ":" + FIXED_HOUR_SUFFIX, "900011"),
                "余额不足不应创建/累加频次计数");
    }

    /** 未传 userId（匿名流量）时整段频次逻辑短路，不读写任何频次 key。 */
    @Test
    void frequencyCap_skippedWhenNoUserId() {
        for (int i = 0; i < 4; i++) {
            assertTrue(budgetService.deduct(900010L, new BigDecimal("100.00"),
                            new BigDecimal("0.10"), FIXED_DATE),
                    "无 userId 时不应受频次限制，第 " + (i + 1) + " 次仍应成功");
        }
        assertEquals("40", redis.opsForValue().get("spent:900010:" + FIXED_SUFFIX),
                "4 次各 0.10 元，spent 应为 40 分");
        assertFalse(redis.hasKey("freq:u:__none__:" + FIXED_HOUR_SUFFIX),
                "无 userId 时脚本不得触碰频次 key");
    }

    /** 固定窗口按自然小时重置：上一小时刷满，下一小时重新获得额度。 */
    @Test
    void frequencyCap_resetsOnNextHour() {
        seedBudget(900010L, "100.00");
        int limit = props.getFrequency().getMaxPerHour();
        for (int i = 0; i < limit; i++) {
            budgetService.deductResult(900010L, new BigDecimal("100.00"),
                    new BigDecimal("0.10"), FREQ_USER, FIXED_TIME);
        }
        assertEquals(BudgetService.DeductResult.FREQ_CAPPED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME));

        LocalDateTime nextHour = FIXED_TIME.plusHours(1);
        assertEquals(BudgetService.DeductResult.SUCCESS,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, nextHour),
                "跨到下一个自然小时应重置频次额度");
        assertEquals("1", redis.opsForHash().get(
                        "freq:u:" + FREQ_USER + ":" + nextHour.format(HOUR_FMT), "900010"),
                "新窗口使用新的小时键");
    }

    /**
     * 频次判断在预算扣减之前：已经刷满的用户即使预算 key 丢失，
     * 也不应触发 NEEDS_SEED 的 DB 重建（这次请求根本不会成交）。
     */
    @Test
    void frequencyCap_isCheckedBeforeBudgetSeed() {
        String freqKey = "freq:u:" + FREQ_USER + ":" + FIXED_HOUR_SUFFIX;
        redis.opsForHash().put(freqKey, "900010",
                String.valueOf(props.getFrequency().getMaxPerHour()));

        assertEquals(BudgetService.DeductResult.FREQ_CAPPED,
                budgetService.deductResult(900010L, new BigDecimal("100.00"),
                        new BigDecimal("0.10"), FREQ_USER, FIXED_TIME),
                "频次超限应直接返回，不需要重建预算");
        assertFalse(redis.hasKey("budget:900010:" + FIXED_SUFFIX),
                "频次超限不应写入 budget key");
        assertFalse(redis.hasKey("spent:900010:" + FIXED_SUFFIX),
                "频次超限不应写入 spent key");
    }
}
