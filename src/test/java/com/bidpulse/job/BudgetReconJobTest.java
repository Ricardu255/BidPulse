package com.bidpulse.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 预算对账集成测试（真连 MySQL + Redis）。
 *
 * <p>用固定的未来日期 {@link #DATE} 做分桶，避免与其他测试写在「今天」的预算 key 互相干扰，
 * 也让计数断言（对账条数 / 告警数 / 修复数）完全确定。
 *
 * <p>覆盖：
 * ① Redis 高于账单（事件未落库）→ 只告警，绝不下调 spent；
 * ② DB 高于 Redis（key 丢失/被重置）→ 按账单修复 budget 与 spent；
 * ③ 差值在容差内 → 视为事件在途，不告警不修复；
 * ④ Redis 脏值 → 跳过，不能当 0 处理（否则会把脏值掩盖成一次「DB > Redis」修复）。
 */
@SpringBootTest
@Tag("integration")
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class BudgetReconJobTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 固定对账日期：其他测试不会往这个日期分桶写 key */
    private static final LocalDate DATE = LocalDate.of(2031, 1, 15);
    private static final String SUFFIX = DATE.format(FMT);

    private static final long AD_OK = 900010L;      // 用于「DB > Redis 修复」
    private static final long AD_CLEAN = 900002L;   // 用于「无偏差」
    private static final long AD_AHEAD = 900011L;   // 用于「Redis > DB 只告警」
    private static final List<Long> TEST_ADS = List.of(AD_OK, AD_CLEAN, AD_AHEAD);

    @Autowired
    private BudgetReconJob job;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRedis() {
        redis.delete(redisKeys());
    }

    @AfterEach
    void cleanUp() {
        redis.delete(redisKeys());
        jdbc.update("DELETE FROM billing_event WHERE ad_id IN (900002, 900010, 900011)");
    }

    private List<String> redisKeys() {
        return TEST_ADS.stream()
                .flatMap(id -> List.of("budget:" + id + ":" + SUFFIX, "spent:" + id + ":" + SUFFIX).stream())
                .toList();
    }

    private void billing(long adId, String eventId, String payPrice) {
        long ts = DATE.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1_000;
        jdbc.update(
                "INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts) "
                        + "VALUES (?,?,?,?,?)",
                eventId, adId, adId, new BigDecimal(payPrice), ts);
    }

    @Test
    void reconcile_alertsWhenRedisAhead_andRepairsWhenDbAhead() {
        // 900010：DB 账单 20 元，Redis 只记了 10 元 → key 丢失，按账单修复
        billing(AD_OK, "recon-repair-900010", "20.00");
        redis.opsForValue().set("budget:" + AD_OK + ":" + SUFFIX, "9000");
        redis.opsForValue().set("spent:" + AD_OK + ":" + SUFFIX, "1000");

        // 900002：DB 与 Redis 都是 5 元 → 无偏差
        billing(AD_CLEAN, "recon-clean-900002", "5.00");
        redis.opsForValue().set("budget:" + AD_CLEAN + ":" + SUFFIX, "9500");
        redis.opsForValue().set("spent:" + AD_CLEAN + ":" + SUFFIX, "500");

        // 900011：Redis 记了 0.50 元，DB 无账单 → 事件未落库，只告警
        redis.opsForValue().set("budget:" + AD_AHEAD + ":" + SUFFIX, "0");
        redis.opsForValue().set("spent:" + AD_AHEAD + ":" + SUFFIX, "50");

        BudgetReconJob.ReconcileSummary summary = job.reconcile(DATE);

        assertEquals(3, summary.adCount(), "三条例广告都应参与对账");
        assertEquals(1, summary.alerted(), "只有 900011 属于 Redis 高于账单");
        assertEquals(1, summary.repaired(), "只有 900010 需要按账单修复");

        // DB > Redis：spent 抬到账单值，budget 按「日预算 100 − 已消耗 20」重算
        assertEquals("2000", redis.opsForValue().get("spent:" + AD_OK + ":" + SUFFIX),
                "spent 应修复为账单汇总 20 元（分）");
        assertEquals("8000", redis.opsForValue().get("budget:" + AD_OK + ":" + SUFFIX),
                "剩余额度应为 100 - 20 = 80 元（分）");

        // Redis > DB：钱已经扣了，绝不下调 spent（否则额度被重复放出）
        assertEquals("50", redis.opsForValue().get("spent:" + AD_AHEAD + ":" + SUFFIX),
                "Redis 高于账单时 spent 不应被下调");

        // 无偏差：不动
        assertEquals("500", redis.opsForValue().get("spent:" + AD_CLEAN + ":" + SUFFIX));
    }

    /** 差值不超过 tolerance-cents（默认 1 分）时不告警也不修复，视为事件在途。 */
    @Test
    void reconcile_ignoresDifferenceWithinTolerance() {
        billing(AD_CLEAN, "recon-tolerance-900002", "5.00");
        redis.opsForValue().set("spent:" + AD_CLEAN + ":" + SUFFIX, "501");

        BudgetReconJob.ReconcileSummary summary = job.reconcile(DATE);

        assertEquals(1, summary.adCount());
        assertEquals(0, summary.alerted(), "1 分差值在默认容差内，不应告警");
        assertEquals(0, summary.repaired(), "1 分差值在默认容差内，不应修复");
        assertEquals("501", redis.opsForValue().get("spent:" + AD_CLEAN + ":" + SUFFIX));
    }

    /**
     * Redis spent 是脏值（非数字）时必须跳过：若当成 0，就会走「DB > Redis」修复分支，
     * 把脏值悄悄掩盖成一次正常修复，真正的异常反而不可见。
     */
    @Test
    void reconcile_skipsDirtySpentValue() {
        billing(AD_OK, "recon-dirty-900010", "10.00");
        redis.opsForValue().set("spent:" + AD_OK + ":" + SUFFIX, "abc");

        BudgetReconJob.ReconcileSummary summary = job.reconcile(DATE);

        assertEquals(0, summary.adCount(), "脏值不参与对账计数");
        assertEquals(0, summary.repaired(), "脏值不能触发修复");
        assertEquals("abc", redis.opsForValue().get("spent:" + AD_OK + ":" + SUFFIX),
                "脏值应保持原样，等待人工排查");
    }
}
