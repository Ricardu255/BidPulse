package com.bidpulse.bid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.entity.Ad;
import lombok.extern.slf4j.Slf4j;
import com.bidpulse.config.BidPulseProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 兜底广告提供者。
 *
 * <p><b>职责边界</b>：只回答两个问题——「现在允许兜底吗」和「兜底广告是哪条」。
 * 原来这两件事和竞价编排、事件组装一起塞在 {@code BidService} 里，
 * 导致主链路类既管交易又管配置数据。
 *
 * <p><b>两级保底</b>：优先读 Redis 的 {@code fallback:ad}（运营可改、无需发版）；
 * Redis 不可用或内容损坏时退回代码里的静态常量，保证<b>永远有广告可返回</b>。
 * 启动时用 {@code SET NX} 播种，只在缺失时写入，不覆盖运营改过的值。
 */
@Slf4j
@Component
public class FallbackAdProvider {

    /** Redis 里的兜底广告 key */
    static final String REDIS_KEY = "fallback:ad";

    /** 静态兜底广告的 JSON 形态，用于启动时播种 */
    private static final String AD_JSON =
            "{\"adId\":999999,\"advertiserId\":0,\"title\":\"兜底保底广告\",\"categoryCode\":\"mobile\",\"bid\":0.01}";

    /** Redis 不可用时的最后保底 */
    private static final Ad STATIC_AD = buildStaticAd();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BidPulseProperties props;
    /** 正常刷新间隔（ms）：运营改 Redis 后最多这么久生效，期间兜底路径不再访问 Redis */
    private final long cacheTtlMs;
    /** 读取失败后的退避（ms）：期间直接用缓存，Redis 故障时兜底路径不会被命令超时拖慢 */
    private final long retryBackoffMs;

    /** 最近一次成功读取的兜底广告；初值为静态保底，任何时刻都不会为空 */
    private volatile Ad cachedAd = STATIC_AD;
    /** 下一次允许访问 Redis 的时刻（epoch ms） */
    private volatile long nextRefreshAt = 0L;

    public FallbackAdProvider(StringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              BidPulseProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
        this.cacheTtlMs = Math.max(0L, props.getFallback().getCacheTtlMs());
        this.retryBackoffMs = Math.max(0L, props.getFallback().getRetryBackoffMs());
        seed();
    }

    /**
     * 兜底总开关。生产语义下恒为 true：任何失败都必须收敛到兜底广告。
     * 置 false 只用于 A/B 对照实验——复现「没有兜底机制的旧实现」，测量改造前的空广告率。
     */
    public boolean enabled() {
        // 运行时读取：控制台“演示开关”可以立即改变兜底行为。
        return props.getFallback().isEnabled();
    }

    /**
     * 当前兜底广告：本地缓存优先，过期才读 Redis；Redis 读取失败时退避并继续用缓存/静态保底。
     *
     * <p><b>为什么不能每次都读 Redis</b>：兜底路径恰恰在依赖出故障时被大量调用，
     * 而 Redis 又是最常见的故障源。每次都同步 GET 会让每个兜底请求白等一次
     * {@code spring.data.redis.timeout}（200ms），把「保底」变成「雪上加霜」。
     * 正常时缓存 5s（运营改值最多延迟 5s 生效），失败后退避 30s 不再触达 Redis。
     */
    public Ad current() {
        long now = System.currentTimeMillis();
        if (now < nextRefreshAt) {
            return cachedAd;
        }
        try {
            String json = redis.opsForValue().get(REDIS_KEY);
            if (json != null && !json.isBlank()) {
                cachedAd = parse(json);
            } else {
                // key 被删除 / 为空：回退静态保底，同样缓存一段时间，避免每请求一次 GET
                cachedAd = STATIC_AD;
            }
            nextRefreshAt = now + cacheTtlMs;
            return cachedAd;
        } catch (Exception e) {
            log.warn("读取 Redis 兜底广告失败，{}ms 内使用本地缓存/静态兜底: {}",
                    retryBackoffMs, e.getMessage());
            nextRefreshAt = now + retryBackoffMs;
            return cachedAd;
        }
    }

    /** 解析运营配置的兜底广告 JSON；字段缺失/非法时抛异常，由 {@link #current()} 回退缓存 */
    private Ad parse(String json) throws Exception {
        Map<?, ?> m = objectMapper.readValue(json, Map.class);
        Ad ad = new Ad();
        ad.setId(Long.parseLong(String.valueOf(m.get("adId"))));
        ad.setAdvertiserId(Integer.parseInt(String.valueOf(m.get("advertiserId"))));
        ad.setTitle(String.valueOf(m.get("title")));
        ad.setCategoryCode(String.valueOf(m.get("categoryCode")));
        ad.setBid(new BigDecimal(String.valueOf(m.get("bid"))));
        return ad;
    }

    /** 启动时把兜底广告写入 Redis（best-effort，Redis 不在也可后续用静态常量） */
    private void seed() {
        try {
            redis.opsForValue().setIfAbsent(REDIS_KEY, AD_JSON);
        } catch (Exception e) {
            log.warn("兜底广告预热到 Redis 失败（将仅使用静态兜底）: {}", e.getMessage());
        }
    }

    private static Ad buildStaticAd() {
        Ad ad = new Ad();
        ad.setId(999999L);
        ad.setAdvertiserId(0);
        ad.setTitle("兜底保底广告");
        ad.setCategoryCode("mobile");
        ad.setBid(BigDecimal.valueOf(0.01));
        return ad;
    }
}
