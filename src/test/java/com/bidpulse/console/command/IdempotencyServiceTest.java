package com.bidpulse.console.command;

import com.bidpulse.api.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 幂等键的三条边界：首次放行、重复 409、Redis 故障 fail-open。
 *
 * <p>fail-open 是刻意取舍：幂等是运维安全增强，Redis 挂掉不应该让「暂停消费」
 * 这类止损操作整体不可用；代价是极端情况下可能重复执行一次。
 */
class IdempotencyServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        service = new IdempotencyService(redis);
    }

    /** 只有真正走到 Redis 的用例才建立 opsForValue 桩，避免干扰「零交互」断言 */
    private void stubOps() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    @DisplayName("首次请求占位成功，键带 scope 前缀并设置 10 分钟 TTL")
    void firstRequestClaimsKey() {
        stubOps();
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertDoesNotThrow(() -> service.requireFresh("dlt-replay", "key-1"));

        verify(values).setIfAbsent(eq("idem:dlt-replay:key-1"), eq("1"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("同一 scope 的重复键返回 409")
    void duplicateKeyConflicts() {
        stubOps();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> service.requireFresh("dlt-replay", "key-1"));
        assertEquals(409, e.getStatus());
    }

    @Test
    @DisplayName("空键直接放行，兼容脚本/老客户端")
    void blankKeyIsAllowed() {
        assertDoesNotThrow(() -> service.requireFresh("dlt-replay", "  "));
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("超长键返回 400，不触达 Redis")
    void tooLongKeyIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.requireFresh("dlt-replay", "x".repeat(129)));
        assertEquals(400, e.getStatus());
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("Redis 故障时 fail-open：记 warn 但不阻断操作")
    void redisFailureFailsOpen() {
        stubOps();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> service.requireFresh("recon-run", "key-1"));
        verify(redis, never()).delete(anyString());
    }
}
