package com.bidpulse.console.query;

import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.testsupport.BidPulseIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台只读池的真库集成测试。
 *
 * <p>验证的不是「配置写对了」，而是三条运行时事实：
 * 主池与只读池确实是两个独立连接池；只读池上的写入会被 MySQL 拒绝；
 * 概览页通过只读池也能正常完成快照查询。
 */
@SpringBootTest
@BidPulseIntegrationTest
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ConsoleReadPoolTest {

    @Autowired
    @Qualifier("dataSource")
    private DataSource mainDataSource;

    @Autowired
    @Qualifier("consoleReadDataSource")
    private DataSource consoleReadDataSource;

    @Autowired
    @Qualifier("consoleReadJdbcTemplate")
    private JdbcTemplate consoleReadJdbc;

    @Autowired
    @Qualifier("consoleReadTransactionTemplate")
    private TransactionTemplate consoleReadTx;

    @Autowired
    private ConsoleOverviewService overviewService;

    @Test
    @DisplayName("主池与控制台只读池是两个独立的 Hikari 池")
    void poolsAreSeparate() {
        assertNotSame(mainDataSource, consoleReadDataSource);
        assertEquals("bidpulseHikari", ((HikariDataSource) mainDataSource).getPoolName());
        assertEquals("bidpulse-console-reader",
                ((HikariDataSource) consoleReadDataSource).getPoolName());
    }

    @Test
    @DisplayName("只读池上的 INSERT 被 MySQL 拒绝")
    void readPoolRejectsWrites() {
        DataAccessException ex = assertThrows(DataAccessException.class, () ->
                consoleReadJdbc.update("INSERT INTO ad_freq(ad_id, cnt) VALUES(?, 1)", 900099L));
        assertTrue(ex.getMessage().toUpperCase().contains("READ ONLY"),
                () -> "期望 MySQL 只读事务错误，实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("只读事务内的 DELETE 同样被拒绝")
    void snapshotTransactionRejectsWrites() {
        assertThrows(DataAccessException.class, () -> consoleReadTx.executeWithoutResult(status ->
                consoleReadJdbc.update("DELETE FROM ad_freq WHERE ad_id = ?", 900099L)));
    }

    @Test
    @DisplayName("概览快照通过只读池读取真实数据")
    void overviewReadsThroughReadPool() {
        OverviewView view = overviewService.overview();

        assertTrue(view.totalAds() >= 5, "夹具至少插入 5 条广告");
        assertTrue(view.bidsToday() >= 0);
        assertTrue(view.kafkaLag() >= -1, "Kafka 不可用时 lag 应降级为 -1");
    }
}
