package com.bidpulse.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台只读池的接线契约（不连数据库）。
 *
 * <p>锁住三件事：池上限独立且很小、连接只读、快照事务确实是
 * REPEATABLE_READ + READ ONLY。任何一条退化，控制台都可能重新影响竞价主链路。
 */
class DataSourceConfigTest {

    private final DataSourceConfig config = new DataSourceConfig();

    @Test
    @DisplayName("只读池使用独立池名、小容量与 readOnly 连接")
    void consoleReadDataSource_isSmallAndReadOnly() {
        try (HikariDataSource dataSource = config.consoleReadDataSource(
                dataSourceProperties(), new BidPulseProperties())) {
            assertEquals("bidpulse-console-reader", dataSource.getPoolName());
            assertTrue(dataSource.isReadOnly(), "只读池必须拒绝写入");
            assertEquals(4, dataSource.getMaximumPoolSize());
            assertEquals(1, dataSource.getMinimumIdle());
            assertEquals(3000, dataSource.getConnectionTimeout());
            assertEquals(2000, dataSource.getValidationTimeout());
        }
    }

    @Test
    @DisplayName("只读查询模板带 2s 语句超时")
    void consoleReadJdbcTemplate_hasQueryTimeout() {
        JdbcTemplate template = config.consoleReadJdbcTemplate(
                mock(DataSource.class), new BidPulseProperties());

        assertEquals(2, template.getQueryTimeout());
    }

    @Test
    @DisplayName("快照事务：READ ONLY + REPEATABLE_READ，并在连接上执行 SET TRANSACTION READ ONLY")
    void consoleReadTransactionTemplate_isReadOnlyRepeatableRead() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        TransactionTemplate template = config.consoleReadTransactionTemplate(
                dataSource, new BidPulseProperties());

        assertTrue(template.isReadOnly());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, template.getIsolationLevel());
        assertEquals(2, template.getTimeout());

        template.executeWithoutResult(status -> {
        });

        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(statement).executeUpdate("SET TRANSACTION READ ONLY");
        verify(connection).commit();
    }

    private static DataSourceProperties dataSourceProperties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:mysql://127.0.0.1:3306/bidpulse");
        properties.setUsername("test");
        properties.setPassword("test");
        return properties;
    }
}
