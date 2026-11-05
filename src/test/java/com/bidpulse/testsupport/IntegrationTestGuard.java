package com.bidpulse.testsupport;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 集成测试隔离守卫。
 *
 * <p>规则（参考 EchoFrame 的 {@code BUSINESS_IT} 守卫，并按 BidPulse 的三件套扩展）：
 * <ul>
 *   <li>{@code BIDPULSE_IT=true} 才允许运行；普通 {@code mvn test} 已由
 *       {@code @Tag("integration")} 排除，若显式启用 {@code -Pfull-tests} 却没设开关，
 *       直接失败而不是静默跳过（避免「全绿」假象）。</li>
 *   <li>JDBC URL 必须是本机 + 库名以 {@code _it} 结尾；绝不回退到 demo 的 {@code bidpulse} 库。</li>
 *   <li>Redis 必须使用 1~15 的 database index，避免清空 demo 的预算/频次 key。</li>
 *   <li>Kafka 主主题与 DLT 主题必须以 {@code bidpulse_it_} 开头，避免消费 demo/生产主题。</li>
 * </ul>
 *
 * <p><b>为什么配错时 fail 而不是 assume</b>：{@code BIDPULSE_IT=true} 表示开发者明确要跑集成测试；
 * 此时 URL/Redis/Kafka 配错是配置错误。静默跳过会制造「测试全绿」的假象，
 * 而这正是隔离守卫要防的事故。
 */
public final class IntegrationTestGuard implements BeforeAllCallback {

    static final String IT_ENV = "BIDPULSE_IT";
    static final String JDBC_ENV = "BIDPULSE_JDBC_URL";
    static final String REDIS_DB_ENV = "BIDPULSE_REDIS_DATABASE";
    static final String KAFKA_TOPIC_ENV = "BIDPULSE_KAFKA_TOPIC";
    static final String KAFKA_DLT_TOPIC_ENV = "BIDPULSE_KAFKA_DLT_TOPIC";

    private static final Pattern LOCAL_IT_JDBC = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/[A-Za-z0-9_]+_it(?:\\?.*)?$");

    @Override
    public void beforeAll(ExtensionContext context) {
        boolean enabled = "true".equalsIgnoreCase(env(IT_ENV));
        if (!enabled && isFullTestsProfile()) {
            fail("已启用 full-tests profile，但 BIDPULSE_IT 未设为 true。"
                    + "请设置 BIDPULSE_IT=true 并指向独立的 *_it 库；"
                    + "如果只想跑单测，去掉 -Pfull-tests。");
        }
        Assumptions.assumeTrue(enabled,
                "集成测试未启用：设置 BIDPULSE_IT=true，并指向独立的 *_it 库（见 README「测试与 CI」）");

        String jdbcUrl = envOrProperty(JDBC_ENV, "spring.datasource.url");
        if (!isValidItJdbcUrl(jdbcUrl)) {
            fail("集成测试 JDBC URL 不合法：" + jdbcUrl
                    + "。必须形如 jdbc:mysql://127.0.0.1:3306/<name>_it?...，且不得指向 demo 库 bidpulse。");
        }

        String redisDb = envOrProperty(REDIS_DB_ENV, "spring.data.redis.database");
        if (!isIsolatedRedisDatabase(redisDb)) {
            fail("集成测试 Redis database 不合法：" + redisDb
                    + "。必须设置 BIDPULSE_REDIS_DATABASE=1..15，避免污染 demo 的预算/频次 key。");
        }

        String topic = envOrProperty(KAFKA_TOPIC_ENV, "bidpulse.kafka.topic");
        String dltTopic = envOrProperty(KAFKA_DLT_TOPIC_ENV, "bidpulse.kafka.dlt-topic");
        if (!isIsolatedKafkaTopic(topic) || !isIsolatedKafkaTopic(dltTopic)) {
            fail("集成测试 Kafka 主题不合法：topic=" + topic + ", dltTopic=" + dltTopic
                    + "。两者都必须以 bidpulse_it_ 开头，避免消费 demo/生产主题。");
        }
    }

    static boolean isValidItJdbcUrl(String url) {
        return url != null && LOCAL_IT_JDBC.matcher(url).matches();
    }

    static boolean isIsolatedRedisDatabase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            int db = Integer.parseInt(value.trim());
            return db >= 1 && db <= 15;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isIsolatedKafkaTopic(String value) {
        return value != null && value.startsWith("bidpulse_it_");
    }

    static boolean isFullTestsProfile() {
        return Boolean.parseBoolean(System.getProperty("bidpulse.full-tests", "false"));
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String envOrProperty(String envName, String propertyName) {
        String value = System.getenv(envName);
        return value != null ? value : System.getProperty(propertyName);
    }
}
