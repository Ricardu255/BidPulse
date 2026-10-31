package com.bidpulse.console.query;

import com.bidpulse.breaker.BreakerGate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 DB / Redis 两条熔断器的状态整理成概览页可展示的只读视图。
 *
 * <p>local 实现返回 CLOSED / OPEN / HALF_OPEN；Sentinel 实现返回 SENTINEL，
 * 表示状态由框架内部托管，避免把“没有读到”误报成 CLOSED。
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
