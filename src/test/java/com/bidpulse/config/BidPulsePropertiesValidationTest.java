package com.bidpulse.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置不变量：非法值必须在启动阶段被拒绝，而不是在运行期静默改变行为。
 *
 * <p>典型例子：把 {@code slow-ratio} 写成 {@code 50}（而不是 {@code 0.5}）时，
 * Sentinel 的慢调用比例阈值会永远无法满足，熔断器看起来「永远不打开」；
 * 没有启动校验时只能靠人肉排查。
 */
class BidPulsePropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertTrue(validator.validate(new BidPulseProperties()).isEmpty(),
                "BidPulseProperties 的默认值必须全部通过校验");
    }

    @Test
    void rejectsSlowRatioOutsideUnitInterval() {
        BidPulseProperties zero = new BidPulseProperties();
        zero.getCircuitBreaker().setSlowRatio(0);
        assertViolation(zero, "slow-ratio");

        BidPulseProperties tooLarge = new BidPulseProperties();
        tooLarge.getCircuitBreaker().setSlowRatio(50);
        assertViolation(tooLarge, "slow-ratio");
    }

    @Test
    void rejectsInvalidCandidateLimit() {
        BidPulseProperties props = new BidPulseProperties();
        props.getBid().setCandidateLimit(0);
        assertViolation(props, "candidate-limit");
    }

    @Test
    void rejectsUnknownCircuitBreakerImpl() {
        BidPulseProperties props = new BidPulseProperties();
        props.getCircuitBreaker().setImpl("resilience4j");
        assertViolation(props, "circuit-breaker.impl");
    }

    @Test
    void rejectsInvalidCron() {
        BidPulseProperties props = new BidPulseProperties();
        props.getKafka().setDltReplayCron("not-a-cron");
        assertViolation(props, "dlt-replay-cron");

        BidPulseProperties cleanup = new BidPulseProperties();
        cleanup.getKafka().setProcessedEventCleanupCron("0 0 0");
        assertViolation(cleanup, "processed-event-cleanup-cron");
    }

    @Test
    void rejectsRetryMultiplierBelowOne() {
        BidPulseProperties props = new BidPulseProperties();
        props.getKafka().setRetryMultiplier(0.5);
        assertViolation(props, "retry-multiplier");
    }

    @Test
    void rejectsTooShortTokenTtl() {
        BidPulseProperties props = new BidPulseProperties();
        props.getAuth().setTokenTtlSeconds(10);
        assertViolation(props, "token-ttl-seconds");
    }

    @Test
    void rejectsZeroFrequencyLimit() {
        BidPulseProperties props = new BidPulseProperties();
        props.getFrequency().setMaxPerHour(0);
        assertViolation(props, "max-per-hour");
    }

    @Test
    void rejectsInvalidAuthRole() {
        BidPulseProperties props = new BidPulseProperties();
        BidPulseProperties.AuthUser user = new BidPulseProperties.AuthUser();
        user.setRole("SUPERUSER");
        props.getAuth().getUsers().put("admin", user);
        assertViolation(props, "role");
    }

    @Test
    void rejectsTopicWithWhitespace() {
        BidPulseProperties props = new BidPulseProperties();
        props.getKafka().setTopic("bad topic");
        assertViolation(props, "bidpulse.kafka.topic");
    }

    @Test
    void rejectsInvalidConsoleReadPoolSize() {
        BidPulseProperties zero = new BidPulseProperties();
        zero.getConsole().getReadDb().setMaxPoolSize(0);
        assertViolation(zero, "max-pool-size");

        BidPulseProperties tooLarge = new BidPulseProperties();
        tooLarge.getConsole().getReadDb().setMaxPoolSize(50);
        assertViolation(tooLarge, "max-pool-size");
    }

    @Test
    void rejectsConsoleReadMinIdleAboveMaxPoolSize() {
        BidPulseProperties props = new BidPulseProperties();
        props.getConsole().getReadDb().setMaxPoolSize(2);
        props.getConsole().getReadDb().setMinIdle(3);
        assertViolation(props, "min-idle 不能大于 max-pool-size");
    }

    @Test
    void rejectsInvalidConsoleReadTimeouts() {
        BidPulseProperties queryTimeout = new BidPulseProperties();
        queryTimeout.getConsole().getReadDb().setQueryTimeoutSeconds(0);
        assertViolation(queryTimeout, "query-timeout-seconds");

        BidPulseProperties connectTimeout = new BidPulseProperties();
        connectTimeout.getConsole().getReadDb().setConnectionTimeoutMs(100);
        assertViolation(connectTimeout, "connection-timeout-ms");
    }

    private void assertViolation(BidPulseProperties props, String messagePart) {
        Set<ConstraintViolation<BidPulseProperties>> violations = validator.validate(props);
        assertFalse(violations.isEmpty(), "应至少有一条校验失败");
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains(messagePart)),
                () -> "期望包含 [" + messagePart + "] 的校验失败，实际=" + violations);
    }
}
