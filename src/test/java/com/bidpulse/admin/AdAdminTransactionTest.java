package com.bidpulse.admin;

import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算变更的“事务回滚不污染 Redis”集成测试。
 *
 * <p>{@code AdAdminService.updateDailyBudget} 在事务 afterCommit 里才同步 Redis；
 * 这里真实连 MySQL + Redis，在事务内改预算后主动抛异常，断言：
 * <ul>
 *   <li>DB 的 daily_budget 回到原值；</li>
 *   <li>当天的 budget/spent key 没有被创建或改写。</li>
 * </ul>
 */
@SpringBootTest
@Tag("integration")
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AdAdminTransactionTest {

    private static final long AD_ID = 900010L;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private AdAdminService service;
    @Autowired
    private AdMapper adMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanRedisKeys() {
        String date = LocalDate.now().format(DATE_FMT);
        redis.delete(List.of("budget:" + AD_ID + ":" + date, "spent:" + AD_ID + ":" + date));
    }

    @Test
    @DisplayName("事务回滚后 DB 预算不变，afterCommit 不写 Redis")
    void rollbackKeepsDbAndSkipsRedisSync() {
        String date = LocalDate.now().format(DATE_FMT);
        String budgetKey = "budget:" + AD_ID + ":" + date;
        String spentKey = "spent:" + AD_ID + ":" + date;
        redis.delete(List.of(budgetKey, spentKey));

        BigDecimal original = adMapper.selectById(AD_ID).getDailyBudget();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThrows(RollbackMarker.class, () -> tx.executeWithoutResult(status -> {
            service.updateDailyBudget(AD_ID, new BigDecimal("200.00"));
            // 事务内可见新值，证明 UPDATE 确实执行过
            Ad inTx = adMapper.selectById(AD_ID);
            assertEquals(0, new BigDecimal("200.00").compareTo(inTx.getDailyBudget()));
            throw new RollbackMarker();
        }));

        Ad after = adMapper.selectById(AD_ID);
        assertEquals(0, original.compareTo(after.getDailyBudget()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(budgetKey)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(spentKey)));
    }

    @Test
    @DisplayName("事务提交后才同步 Redis")
    void commitSyncsRedis() {
        String date = LocalDate.now().format(DATE_FMT);
        String budgetKey = "budget:" + AD_ID + ":" + date;
        String spentKey = "spent:" + AD_ID + ":" + date;
        redis.delete(List.of(budgetKey, spentKey));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> service.updateDailyBudget(AD_ID, new BigDecimal("200.00")));

        assertEquals(0, new BigDecimal("200.00").compareTo(
                adMapper.selectById(AD_ID).getDailyBudget()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(budgetKey)));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(spentKey)));
    }

    /** 只用于让 TransactionTemplate 回滚。 */
    private static final class RollbackMarker extends RuntimeException {
        private RollbackMarker() {
            super("rollback for test");
        }
    }
}
