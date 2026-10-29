package com.bidpulse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BidPulseProperties} 的绑定测试：不启动 Web/MySQL/Redis/Kafka。
 *
 * <p>验证两件事：kebab-case 的嵌套配置能绑到类型字段上；
 * 未显式配置时默认值仍与代码内默认一致（避免收口后默认值漂移）。
 */
class BidPulsePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BidPulseProperties.class);

    @Test
    @DisplayName("嵌套 kebab-case 配置正确绑定")
    void bindsNestedKebabCase() {
        runner.withPropertyValues(
                        "bidpulse.min-increment=0.02",
                        "bidpulse.timeout.total-ms=800",
                        "bidpulse.bid.candidate-limit=7",
                        "bidpulse.circuit-breaker.impl=local",
                        "bidpulse.circuit-breaker.db-slow-rt-ms=650",
                        "bidpulse.fallback.enabled=false",
                        "bidpulse.fallback.cache-ttl-ms=1234",
                        "bidpulse.fallback.retry-backoff-ms=5678",
                        "bidpulse.kafka.dlt-topic=bid_event.dlt-test",
                        "bidpulse.kafka.processed-event-retention-days=3",
                        "bidpulse.budget.key-ttl-seconds=3600",
                        "bidpulse.budget.cleanup.retention-days=5",
                        "bidpulse.budget.reset.cron=0 3 0 * * ?",
                        "bidpulse.frequency.enabled=false",
                        "bidpulse.frequency.max-per-hour=7",
                        "bidpulse.frequency.key-ttl-seconds=900")
                .run(ctx -> {
                    BidPulseProperties p = ctx.getBean(BidPulseProperties.class);
                    assertEquals(0, new BigDecimal("0.02").compareTo(p.getMinIncrement()));
                    assertEquals(800, p.getTimeout().getTotalMs());
                    assertEquals(7, p.getBid().getCandidateLimit());
                    assertEquals("local", p.getCircuitBreaker().getImpl());
                    assertEquals(650.0, p.getCircuitBreaker().getDbSlowRtMs());
                    assertEquals(false, p.getFallback().isEnabled());
                    assertEquals(1234, p.getFallback().getCacheTtlMs());
                    assertEquals(5678, p.getFallback().getRetryBackoffMs());
                    assertEquals("bid_event.dlt-test", p.getKafka().getDltTopic());
                    assertEquals(3, p.getKafka().getProcessedEventRetentionDays());
                    assertEquals(3600, p.getBudget().getKeyTtlSeconds());
                    assertEquals(5, p.getBudget().getCleanup().getRetentionDays());
                    assertEquals("0 3 0 * * ?", p.getBudget().getReset().getCron());
                    assertEquals(false, p.getFrequency().isEnabled());
                    assertEquals(7, p.getFrequency().getMaxPerHour());
                    assertEquals(900, p.getFrequency().getKeyTtlSeconds());
                });
    }

    @Test
    @DisplayName("未配置时默认值与 application.yml 口径一致")
    void defaultsMatchYaml() {
        runner.run(ctx -> {
            BidPulseProperties p = ctx.getBean(BidPulseProperties.class);
            assertEquals(0, new BigDecimal("0.01").compareTo(p.getMinIncrement()));
            assertEquals(1000, p.getTimeout().getTotalMs());
            assertEquals(50, p.getBid().getCandidateLimit());
            assertEquals("sentinel", p.getCircuitBreaker().getImpl());
            assertEquals(800.0, p.getCircuitBreaker().getDbSlowRtMs());
            assertEquals(true, p.getFallback().isEnabled());
            assertEquals(5000, p.getFallback().getCacheTtlMs());
            assertEquals(30000, p.getFallback().getRetryBackoffMs());
            assertEquals("bid_event", p.getKafka().getTopic());
            assertEquals("bid_event.DLT", p.getKafka().getDltTopic());
            assertEquals(8, p.getKafka().getProcessedEventRetentionDays());
            assertEquals(2, p.getBudget().getCleanup().getRetentionDays());
            assertEquals(true, p.getFrequency().isEnabled());
            assertEquals(3, p.getFrequency().getMaxPerHour());
            assertEquals(7200, p.getFrequency().getKeyTtlSeconds());
        });
    }
}
