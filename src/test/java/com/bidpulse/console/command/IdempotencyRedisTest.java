package com.bidpulse.console.command;

import com.bidpulse.api.BizException;
import com.bidpulse.testsupport.BidPulseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等键的真实 Redis 集成测试：验证 {@code SET NX PX} 的原子占位语义。
 *
 * <p>单测只能证明「返回 false 时抛 409」；这里确认真实 Redis 下同 key 第二次确实
 * 拿不到占位，并且 scope 不同的相同 key 互不影响。
 */
@SpringBootTest
@BidPulseIntegrationTest
class IdempotencyRedisTest {

    @Autowired
    private IdempotencyService service;
    @Autowired
    private StringRedisTemplate redis;

    private final String key = "it-idem-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        redis.delete(List.of("idem:it-scope-a:" + key, "idem:it-scope-b:" + key));
    }

    @Test
    @DisplayName("同一 scope + key 第二次返回 409；不同 scope 不受影响")
    void duplicateKeyIsRejectedByRealRedis() {
        assertDoesNotThrow(() -> service.requireFresh("it-scope-a", key));

        BizException e = assertThrows(BizException.class,
                () -> service.requireFresh("it-scope-a", key));
        assertEquals(409, e.getStatus());

        assertDoesNotThrow(() -> service.requireFresh("it-scope-b", key),
                "scope 是幂等键命名空间的一部分，不能被别的操作误伤");
    }
}
