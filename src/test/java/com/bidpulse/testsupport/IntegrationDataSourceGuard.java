package com.bidpulse.testsupport;

import org.springframework.boot.test.context.TestConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 第二道防线：直接校验应用实际拿到的 {@link DataSource}，而不是只信环境变量。
 *
 * <p>{@link IntegrationTestGuard} 校验的是 {@code BIDPULSE_JDBC_URL}；
 * 但如果测试通过 {@code @SpringBootTest(properties = ...)} 或系统属性覆盖了
 * {@code spring.datasource.url}，环境变量守卫就看不到真实连接。这里在上下文启动时
 * 打开一条真实连接，用 JDBC metadata 的 URL 和 catalog 再校验一次，确保测试代码
 * 不可能悄悄跑到 demo 库上。
 */
@TestConfiguration
public class IntegrationDataSourceGuard {

    public IntegrationDataSourceGuard(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (!IntegrationTestGuard.isValidItJdbcUrl(url)) {
                throw new IllegalStateException(
                        "集成测试实际连接的 JDBC URL 不是本机 *_it 库：" + url);
            }
            String catalog = connection.getCatalog();
            if (catalog == null || !catalog.endsWith("_it")) {
                throw new IllegalStateException(
                        "集成测试实际连接的数据库名不以 _it 结尾：" + catalog);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法校验集成测试数据库连接", e);
        }
    }
}
