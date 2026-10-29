package com.bidpulse.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kafka 消费端重试策略单测。
 *
 * <p>{@link org.springframework.kafka.listener.DefaultErrorHandler} 不提供 backOff 的读取接口，
 * 所以直接测 {@link KafkaErrorHandlingConfig#retryBackOff} 这个工厂：策略参数是配置契约本身，
 * 用反射去挖 spring-kafka 内部字段（failureTracker → backOff）反而更脆。
 */
class KafkaErrorHandlingConfigTest {

    @Test
    void retryBackOff_usesDocumentedDefaults() {
        ExponentialBackOffWithMaxRetries backOff =
                KafkaErrorHandlingConfig.retryBackOff(new BidPulseProperties().getKafka());

        assertEquals(5, backOff.getMaxRetries(), "默认重试 5 次");
        assertEquals(1000L, backOff.getInitialInterval(), "首次重试间隔 1s");
        assertEquals(2.0, backOff.getMultiplier(), "每次翻倍");
        assertEquals(16000L, backOff.getMaxInterval(), "单次间隔上限 16s");
    }

    @Test
    void retryBackOff_honorsConfiguredValues() {
        BidPulseProperties.Kafka cfg = new BidPulseProperties().getKafka();
        cfg.setRetryInitialMs(300L);
        cfg.setRetryMultiplier(3.0);
        cfg.setRetryMaxMs(5000L);
        cfg.setRetryMaxAttempts(4);

        ExponentialBackOffWithMaxRetries backOff = KafkaErrorHandlingConfig.retryBackOff(cfg);

        assertEquals(4, backOff.getMaxRetries());
        assertEquals(300L, backOff.getInitialInterval());
        assertEquals(3.0, backOff.getMultiplier());
        assertEquals(5000L, backOff.getMaxInterval());
    }

    /** 非法配置（0 / 负数）必须钳到安全值，不能构造出「不重试或无限等待」的策略。 */
    @Test
    void retryBackOff_clampsInvalidValues() {
        BidPulseProperties.Kafka cfg = new BidPulseProperties().getKafka();
        cfg.setRetryInitialMs(0L);
        cfg.setRetryMultiplier(0.5);
        cfg.setRetryMaxMs(-1L);
        cfg.setRetryMaxAttempts(0);

        ExponentialBackOffWithMaxRetries backOff = KafkaErrorHandlingConfig.retryBackOff(cfg);

        assertEquals(1, backOff.getMaxRetries(), "重试次数至少 1 次");
        assertEquals(1L, backOff.getInitialInterval(), "初始间隔至少 1ms");
        assertEquals(1.0, backOff.getMultiplier(), "倍数不得小于 1（不能递减退避）");
        assertEquals(1L, backOff.getMaxInterval(), "上限至少 1ms");
    }
}
