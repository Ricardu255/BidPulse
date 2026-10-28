package com.bidpulse.config;

import com.bidpulse.breaker.BreakerGate;
import com.bidpulse.breaker.CircuitBreaker;
import com.bidpulse.breaker.LocalBreakerGate;
import com.bidpulse.breaker.SentinelBreakerGate;
import com.bidpulse.breaker.SentinelResources;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 熔断门装配：按 {@code bidpulse.circuit-breaker.impl} 决定注入哪套实现。
 *
 * <p>DB 与 Redis 拿到<b>两个互相独立</b>的 Gate（两个不同资源名 / 两个不同熔断器）。
 * 这是分层熔断的关键：Redis 挂了不该把 DB 检索一起短路，反之亦然。
 *
 * <p><b>注入约定</b>：容器里有两个 {@link BreakerGate} Bean（{@code dbBreakerGate} /
 * {@code redisBreakerGate}），<b>按类型注入必须带 {@code @Qualifier}</b>，
 * 否则启动即报 {@code NoUniqueBeanDefinitionException}。新增注入点时直接照抄
 * {@code BidService} 构造函数里的写法。
 *
 * <p>切实现只需改一行配置、重启：
 * <pre>
 *   bidpulse.circuit-breaker.impl: sentinel   # 默认
 *   bidpulse.circuit-breaker.impl: local      # 自写熔断器（对照基线）
 * </pre>
 */
@Slf4j
@Configuration
public class CircuitBreakerConfig {

    /** DB 候选检索的熔断门：慢调用阈值对齐 SQL 的 MAX_EXECUTION_TIME(800ms) */
    @Bean("dbBreakerGate")
    public BreakerGate dbBreakerGate(BidPulseProperties props) {
        return build(SentinelResources.DB_CANDIDATES, props);
    }

    /** Redis 预算扣减的熔断门：慢调用阈值须显著小于 Lettuce 的 200ms 命令超时 */
    @Bean("redisBreakerGate")
    public BreakerGate redisBreakerGate(BidPulseProperties props) {
        return build(SentinelResources.REDIS_BUDGET, props);
    }

    private BreakerGate build(String resource, BidPulseProperties props) {
        BidPulseProperties.CircuitBreakerSettings cb = props.getCircuitBreaker();
        if ("local".equalsIgnoreCase(cb.getImpl())) {
            log.warn("熔断实现 = local（自写 CircuitBreaker，对照基线）：resource={}，连续失败 {} 次打开，冷却 {}ms",
                    resource, cb.getFailureThreshold(), cb.getCooldownMs());
            return new LocalBreakerGate(resource, new CircuitBreaker(cb.getFailureThreshold(), cb.getCooldownMs()));
        }
        log.info("熔断实现 = sentinel：resource={}（阈值见 bidpulse.circuit-breaker.*）", resource);
        return new SentinelBreakerGate(resource);
    }
}
