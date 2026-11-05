package com.bidpulse.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次竞价的决策轨迹：给控制台「为什么是它赢 / 为什么走兜底」提供结构化证据。
 *
 * <p><b>边界</b>：只在控制台发起的竞价里采集（{@code BidService.bid(req, true)}），
 * 外部 {@code POST /bid} 不生成、不返回、不落库，热路径没有额外序列化开销。
 *
 * <p><b>体积与隐私</b>：候选最多保留 10 条，只记广告 ID、出价、成交价与裁决结果，
 * 不记用户标识、兴趣、IP 等请求上下文；轨迹随 {@code bid_log} 异步落库，可丢可采样。
 *
 * @param outcome        WIN / FALLBACK / EMPTY
 * @param fallbackReason 兜底原因代码；成交时为 null。前端负责映射成中文文案
 * @param steps          按时间顺序的阶段摘要，用于时间线展示
 * @param timings        检索 / 计费 / 总耗时，单位毫秒
 */
public record BidTrace(
        String requestId,
        long ts,
        String outcome,
        String fallbackReason,
        int candidateCnt,
        int matchCnt,
        Long winnerAdId,
        BigDecimal winBid,
        BigDecimal payPrice,
        int priceMode,
        List<Candidate> candidates,
        List<Step> steps,
        Timings timings) {

    /** 单个候选的裁决结果：SUCCESS / INSUFFICIENT / FREQ_CAPPED / NEEDS_SEED / REDIS_ERROR。 */
    public record Candidate(long adId, BigDecimal bid, BigDecimal price, String result) {
    }

    /** 时间线阶段：检索 → 预热 → 扣减 → 成交/兜底。 */
    public record Step(String stage, String detail, int costMs) {
    }

    public record Timings(int dbMs, int redisMs, int totalMs) {
    }
}
