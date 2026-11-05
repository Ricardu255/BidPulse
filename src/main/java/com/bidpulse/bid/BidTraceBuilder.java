package com.bidpulse.bid;

import com.bidpulse.api.dto.BidTrace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link BidTrace} 的构建器：主链路在每个决策点调用，最后一次性生成不可变轨迹。
 *
 * <p>只做内存对象拼装，不做序列化、不碰 IO；即使控制台并发调用，开销也只是一次
 * 小数组拷贝。候选列表封顶 {@value #MAX_CANDIDATES} 条，避免候选上限调大后
 * 单条轨迹无限膨胀。
 */
final class BidTraceBuilder {

    private static final int MAX_CANDIDATES = 10;

    private final String requestId;
    private final long ts = System.currentTimeMillis();
    private final List<BidTrace.Candidate> candidates = new ArrayList<>();
    private final List<BidTrace.Step> steps = new ArrayList<>();

    private int candidateCnt;
    private int matchCnt;
    private int dbMs;
    private int redisMs;
    private String outcome = "EMPTY";
    private String fallbackReason;
    private Long winnerAdId;
    private BigDecimal winBid;
    private BigDecimal payPrice;
    private int priceMode;

    BidTraceBuilder(String requestId) {
        this.requestId = requestId;
    }

    void retrieveFailed(int costMs) {
        this.dbMs = costMs;
        steps.add(new BidTrace.Step("检索", "候选检索失败 / 熔断短路", costMs));
    }

    void retrieved(int count, int costMs) {
        this.candidateCnt = count;
        this.dbMs = costMs;
        steps.add(new BidTrace.Step("检索", "命中候选 " + count + " 条", costMs));
    }

    void matched(int count) {
        this.matchCnt = count;
        if (count != candidateCnt) {
            steps.add(new BidTrace.Step("过滤", "定向过滤后 " + count + " 条", 0));
        }
    }

    void warmed(int count) {
        steps.add(new BidTrace.Step("预热", "候选预算 key 已就绪（" + count + " 条）", 0));
    }

    /**
     * 记录单个候选的扣减裁决。
     *
     * <p>{@code price} 是本次为它计算的成交价；候选进入扣减前就已算好，
     * 因此 Redis 异常时也能说明「本来打算出多少钱」。
     */
    void candidate(long adId, BigDecimal bid, BigDecimal price, String result) {
        if (candidates.size() >= MAX_CANDIDATES) {
            return;
        }
        candidates.add(new BidTrace.Candidate(adId, bid, price, result));
    }

    void won(long adId, BigDecimal winBid, BigDecimal payPrice, int priceMode, int billMs) {
        this.outcome = "WIN";
        this.winnerAdId = adId;
        this.winBid = winBid;
        this.payPrice = payPrice;
        this.priceMode = priceMode;
        this.redisMs = billMs;
        // 胜出候选也进列表：前端看到的「候选裁决」才是完整的，不会只有失败者
        candidate(adId, winBid, payPrice, "SUCCESS");
        String mode = priceMode == 1 ? "二价" : "一价";
        steps.add(new BidTrace.Step("成交",
                mode + "成交 " + payPrice + " 元（胜出价 " + winBid + "）", billMs));
    }

    void fallback(String reason, int billMs) {
        this.outcome = "FALLBACK";
        this.fallbackReason = reason;
        this.redisMs = billMs;
        steps.add(new BidTrace.Step("兜底", "返回兜底广告：" + reason, billMs));
    }

    void empty(String reason, int billMs) {
        this.outcome = "EMPTY";
        this.fallbackReason = reason;
        this.redisMs = billMs;
        steps.add(new BidTrace.Step("空广告", "未返回广告：" + reason, billMs));
    }

    BidTrace build(int totalMs) {
        return new BidTrace(
                requestId, ts, outcome, fallbackReason,
                candidateCnt, matchCnt, winnerAdId, winBid, payPrice, priceMode,
                List.copyOf(candidates), List.copyOf(steps),
                new BidTrace.Timings(dbMs, redisMs, totalMs));
    }
}
