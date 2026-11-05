package com.bidpulse.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 双连接池：竞价/消费主池 + 控制台只读池。
 *
 * <p><b>为什么不再让控制台共用主池</b>：控制台的概览页一次要跑 5 条聚合查询，
 * 数据浏览接口还允许 LIMIT 200 的任意白名单表查询。它们和竞价、Kafka 消费共享
 * 一个 20 连接的池时，一次慢查询/一次误操作就能把连接占满，竞价请求排队到
 * {@code connection-timeout} 后直接失败 —— 只读报表把写入热路径拖垮，是最典型的
 * 「非核心功能放大核心故障」。
 *
 * <p>只读池做了三层约束：
 * <ol>
 *   <li>池上限默认 4（可配），即使控制台被打爆也最多占用 4 个连接；</li>
 *   <li>{@code HikariConfig.readOnly=true}：MySQL Connector/J 会对每个连接执行
 *       {@code SET SESSION TRANSACTION READ ONLY}，误写直接被数据库拒绝；</li>
 *   <li>{@code JdbcTemplate.queryTimeout=2s}：单条语句超时，避免报表扫全表。</li>
 * </ol>
 *
 * <p><b>为什么显式声明两个 DataSource / JdbcTemplate</b>：Spring Boot 的
 * {@code DataSourceAutoConfiguration} 在容器里已存在任意 {@code DataSource} 时整体退让，
 * {@code JdbcTemplateAutoConfiguration} 同理。只声明只读池会让主池消失；因此这里把
 * 主池与主 {@link JdbcTemplate} 一并显式声明，并用 {@link Primary} 保证 MyBatis、
 * {@code @Transactional}、Actuator 健康检查仍然绑定主池。
 *
 * <p><b>为什么事务管理器不注册成 Bean</b>：容器里出现第二个
 * {@code PlatformTransactionManager} 会让 {@code @Transactional} 的按类型注入产生歧义
 * （除非每个注入点都加 {@code @Qualifier}）。只读事务管理器仅被
 * {@link TransactionTemplate} 持有，不参与全局事务解析。
 *
 * <p><b>诚实边界</b>：这里只做到「同一个数据库账号 + 只读会话」。真正的租户隔离
 * 应给控制台单独的只读数据库账号（仅 SELECT 权限），属于部署配置，不在代码内硬编码。
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    /**
     * 主连接池：竞价、Kafka 消费、后台任务共用。
     *
     * <p>{@code @ConfigurationProperties("spring.datasource.hikari")} 把 application.yml
     * 里的 pool-name / maximum-pool-size / leak-detection-threshold 等原样绑定到 Hikari。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 控制台只读池。连接信息复用 {@code spring.datasource.*}，容量与超时独立。
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource consoleReadDataSource(DataSourceProperties properties,
                                                  BidPulseProperties props) {
        BidPulseProperties.Console.ReadDb cfg = props.getConsole().getReadDb();
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("bidpulse-console-reader");
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(cfg.getMaxPoolSize());
        ds.setMinimumIdle(cfg.getMinIdle());
        ds.setConnectionTimeout(cfg.getConnectionTimeoutMs());
        // Hikari 要求 validationTimeout >= 250ms，且通常不大于 connectionTimeout
        ds.setValidationTimeout(Math.min(2000, cfg.getConnectionTimeoutMs()));
        return ds;
    }

    /**
     * 控制台查询模板：语句级超时是只读池最有效的一道闸门
     * （MySQL 驱动会把 queryTimeout 转成服务端执行时间上限）。
     */
    @Bean
    public JdbcTemplate consoleReadJdbcTemplate(
            @Qualifier("consoleReadDataSource") DataSource dataSource,
            BidPulseProperties props) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(props.getConsole().getReadDb().getQueryTimeoutSeconds());
        return template;
    }

    /**
     * 概览快照事务：把多次聚合查询放进同一个 REPEATABLE_READ 只读事务，
     * 避免「请求数、消耗、趋势」分别读取时看到不同的时间点，拼出一个自相矛盾的概览。
     *
     * <p>只读约束由连接池负责：Hikari 的 {@code readOnly=true} 会让 Connector/J 在会话上
     * 执行 {@code SET SESSION TRANSACTION READ ONLY}，写入直接被数据库拒绝。
     * 这里<b>不能</b>再调用 {@code setEnforceReadOnly(true)}：连接已经是只读时，
     * Connector/J 会把事务开始时的 {@code SET TRANSACTION READ ONLY} 当成
     * 「只读连接上的写语句」直接抛错，导致概览快照事务根本开不起来。
     *
     * <p>注意：本地 JDBC 事务不会因 {@code setTimeout} 自动回滚（
     * {@code DataSourceTransactionManager} 只把超时记在 ConnectionHolder 上，不做强制中断）。
     * 真正兜底的是 {@link #consoleReadJdbcTemplate} 的语句超时与 JDBC URL 的
     * {@code socketTimeout}；这里保留 timeout 是为了语义完整，并在未来切换 JTA 时仍然生效。
     */
    @Bean
    public TransactionTemplate consoleReadTransactionTemplate(
            @Qualifier("consoleReadDataSource") DataSource dataSource,
            BidPulseProperties props) {
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        txManager.afterPropertiesSet();

        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setTimeout(props.getConsole().getReadDb().getQueryTimeoutSeconds());
        return template;
    }
}
