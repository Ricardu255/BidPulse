package com.bidpulse.console.query;

import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.console.dto.TrendPoint;
import com.bidpulse.infra.kafka.KafkaLagQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概览快照的语义测试（不连数据库）。
 *
 * <p>重点不是 SQL 文本，而是「哪些查询必须共享同一个事务快照」以及
 * 「Kafka/健康检查不能被卷进只读事务」—— 这两点决定了概览页在并发写入下
 * 会不会展示自相矛盾的资金数字，以及控制台慢调用会不会占住只读连接。
 */
class ConsoleOverviewServiceTest {

    private final AtomicBoolean insideTx = new AtomicBoolean();
    private FakeJdbc jdbc;
    private TransactionTemplate snapshotTx;
    private KafkaLagQueryService kafkaLag;
    private HealthEndpoint healthEndpoint;
    private BreakerStatusProvider breakerStatusProvider;
    private ConsoleOverviewService service;

    @BeforeEach
    void setUp() {
        jdbc = new FakeJdbc(insideTx::get);
        snapshotTx = mock(TransactionTemplate.class);
        when(snapshotTx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            insideTx.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                insideTx.set(false);
            }
        });
        kafkaLag = mock(KafkaLagQueryService.class);
        healthEndpoint = mock(HealthEndpoint.class);
        breakerStatusProvider = mock(BreakerStatusProvider.class);
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(breakerStatusProvider.states())
                .thenReturn(Map.of("dbBreaker", "CLOSED", "redisBreaker", "CLOSED"));
        service = new ConsoleOverviewService(
                jdbc, snapshotTx, kafkaLag, healthEndpoint, breakerStatusProvider);
    }

    @Test
    @DisplayName("5 个数据库查询都在同一个快照事务内，Kafka/健康检查在事务外")
    void overview_readsDatabaseInsideOneSnapshot() {
        when(kafkaLag.totalLag()).thenAnswer(invocation -> {
            assertFalse(insideTx.get(), "Kafka lag 不应占用只读事务");
            return 7L;
        });
        when(healthEndpoint.health()).thenAnswer(invocation -> {
            assertFalse(insideTx.get(), "健康检查不应占用只读事务");
            return Health.up().build();
        });

        OverviewView view = service.overview();

        assertEquals(5, jdbc.reads, "请求数/广告数/消耗/P95/趋势都应在事务内读取");
        assertEquals(10, view.bidsToday());
        assertEquals(2, view.fallbackToday());
        assertEquals(0.2, view.fallbackRate(), 1e-9);
        assertEquals(new BigDecimal("12.34"), view.spendToday());
        assertEquals(50, view.p95CostMs(), "P95 取排序后的 ceil(2*0.95)-1 位");
        assertEquals(5, view.totalAds());
        assertEquals(3, view.activeAds());
        assertEquals(1, view.pausedAds());
        assertEquals(1, view.exhaustedAds());
        assertEquals(7, view.kafkaLag());
        assertEquals("UP", view.health().get("overall"));
        assertEquals("CLOSED", view.health().get("dbBreaker"));
        assertEquals(List.of(new TrendPoint("10:00", 10, 2)), view.trend());
    }

    @Test
    @DisplayName("Kafka lag 查询失败时降级为 -1，不影响其余概览字段")
    void overview_degradesKafkaLagFailure() {
        when(kafkaLag.totalLag()).thenThrow(new RuntimeException("broker down"));

        OverviewView view = service.overview();

        assertEquals(-1, view.kafkaLag());
        assertEquals(10, view.bidsToday());
        assertEquals(new BigDecimal("12.34"), view.spendToday());
    }

    @Test
    @DisplayName("数据库查询失败直接抛出，不返回半成品概览")
    void overview_propagatesDatabaseFailure() {
        jdbc.failOnBidLog = true;

        assertThrows(IllegalStateException.class, () -> service.overview());
        verify(kafkaLag, never()).totalLag();
    }

    @Test
    @DisplayName("最近竞价限制最多 50 行，且走只读模板")
    void recentBids_capsLimit() {
        JdbcTemplate plain = mock(JdbcTemplate.class);
        when(plain.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        ConsoleOverviewService readOnly = new ConsoleOverviewService(
                plain, snapshotTx, kafkaLag, healthEndpoint, breakerStatusProvider);

        assertTrue(readOnly.recentBids(999).isEmpty());

        org.mockito.ArgumentCaptor<Object[]> args =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(plain).query(any(String.class), any(RowMapper.class), args.capture());
        assertEquals(50, args.getValue()[0]);
    }

    /**
     * 内存版 JdbcTemplate：返回固定结果，并记录每次读取时是否处于快照事务中。
     */
    private static final class FakeJdbc extends JdbcTemplate {

        private final BooleanSupplier insideTx;
        private int reads;
        private boolean failOnBidLog;

        private FakeJdbc(BooleanSupplier insideTx) {
            this.insideTx = insideTx;
        }

        private void record() {
            reads++;
            assertTrue(insideTx.getAsBoolean(), "数据库查询必须在快照事务内执行");
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper) {
            // 不带绑定参数的版本（adCounts）不会走 varargs 重载，必须显式转接，
            // 否则会落到无 DataSource 的基类实现。
            return queryForObject(sql, rowMapper, new Object[0]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            record();
            if (sql.contains("FROM bid_log")) {
                if (failOnBidLog) {
                    throw new IllegalStateException("simulated db failure");
                }
                return (T) new long[]{10, 2};
            }
            if (sql.contains("FROM ad")) {
                return (T) new long[]{5, 3, 1, 1};
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            record();
            if (sql.contains("FROM billing_event")) {
                return (T) new BigDecimal("12.34");
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            record();
            if (sql.contains("cost_ms")) {
                return (List<T>) List.of(40, 50);
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            record();
            if (sql.contains("DATE_FORMAT(bid_at")) {
                return (List<T>) List.of(new TrendPoint("10:00", 10, 2));
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }
    }
}
