package com.bidpulse.bid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.config.BidPulseProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底广告缓存单测 —— 不连 Redis。
 *
 * <p>锁住两条行为：
 * <ol>
 *   <li>缓存窗口内<b>只读一次 Redis</b>：否则 Redis 故障时每个兜底请求都要白等一次 200ms 超时；</li>
 *   <li>读取失败后<b>返回静态兜底并退避</b>：下一次调用不再触达 Redis。</li>
 * </ol>
 */
class FallbackAdProviderTest {

    private static final String AD_JSON =
            "{\"adId\":777,\"advertiserId\":7,\"title\":\"t\",\"categoryCode\":\"c\",\"bid\":0.01}";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @Test
    @DisplayName("缓存窗口内只读一次 Redis")
    void current_readsRedisOnceWithinTtl() {
        when(redis.opsForValue()).thenReturn(ops);
        doReturn(AD_JSON).when(ops).get(FallbackAdProvider.REDIS_KEY);

        FallbackAdProvider provider = new FallbackAdProvider(redis, new ObjectMapper(),
                propsWith(60_000, 60_000));

        assertEquals(Long.valueOf(777L), provider.current().getId());
        assertEquals(Long.valueOf(777L), provider.current().getId());
        verify(ops, times(1)).get(FallbackAdProvider.REDIS_KEY);
    }

    @Test
    @DisplayName("Redis 读取异常 → 返回静态兜底，并在退避窗口内不再访问 Redis")
    void current_backsOffAfterFailure() {
        when(redis.opsForValue()).thenReturn(ops);
        doThrow(new RuntimeException("redis down")).when(ops).get(FallbackAdProvider.REDIS_KEY);

        FallbackAdProvider provider = new FallbackAdProvider(redis, new ObjectMapper(),
                propsWith(5_000, 60_000));

        assertEquals(Long.valueOf(999999L), provider.current().getId(), "应回退到静态兜底广告");
        assertEquals(Long.valueOf(999999L), provider.current().getId());
        verify(ops, times(1)).get(FallbackAdProvider.REDIS_KEY);
    }

    private static BidPulseProperties propsWith(long cacheTtlMs, long retryBackoffMs) {
        BidPulseProperties props = new BidPulseProperties();
        props.getFallback().setCacheTtlMs(cacheTtlMs);
        props.getFallback().setRetryBackoffMs(retryBackoffMs);
        return props;
    }
}
