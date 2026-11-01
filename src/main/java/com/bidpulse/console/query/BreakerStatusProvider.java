package com.bidpulse.console.query;

import com.bidpulse.breaker.BreakerGate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 DB / Redis 两条熔断器的状态整理成概览页可展示的只读视图。
 *
 * <p>local 与 Sentinel 实现都返回 CLOSED / OPEN / HALF_OPEN；Sentinel 通过
 * 状态变更观察者同步真实状态，不再用占位值表示“读不到”。
 */
@Component
public class BreakerStatusProvider {

    private final BreakerGate dbBreaker;
    private final BreakerGate redisBreaker;

    public BreakerStatusProvider(@Qualifier("dbBreakerGate") BreakerGate dbBreaker,
                                 @Qualifier("redisBreakerGate") BreakerGate redisBreaker) {
        this.dbBreaker = dbBreaker;
        this.redisBreaker = redisBreaker;
    }

    public Map<String, String> states() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("dbBreaker", dbBreaker.state());
        out.put("redisBreaker", redisBreaker.state());
        return out;
    }
}
