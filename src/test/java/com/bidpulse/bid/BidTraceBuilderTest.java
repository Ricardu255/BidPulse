package com.bidpulse.bid;

import com.bidpulse.api.dto.BidTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策轨迹构建器测试：锁住「成交 / 兜底 / 空广告」三种收尾，以及候选封顶。
 *
 * <p>轨迹是控制台的可解释性素材，不是计费依据；这里验证的是叙事完整性：
 * 候选裁决、兜底原因、耗时分解都要能在同一条轨迹里对上。
 */
class BidTraceBuilderTest {

    @Test
    @DisplayName("成交轨迹：胜出候选标记 SUCCESS，二价与耗时完整")
    void winTrace() {
        BidTraceBuilder builder = new BidTraceBuilder("req-win");
        builder.retrieved(4, 9);
        builder.matched(4);
        builder.warmed(4);
        builder.candidate(106L, new BigDecimal("1.00"), new BigDecimal("0.81"), "INSUFFICIENT");
        builder.candidate(123L, new BigDecimal("0.80"), new BigDecimal("0.81"), "FREQ_CAPPED");
        builder.won(110L, new BigDecimal("1.50"), new BigDecimal("1.01"), 1, 12);

        BidTrace trace = builder.build(23);

        assertEquals("WIN", trace.outcome());
        assertNull(trace.fallbackReason());
        assertEquals(4, trace.candidateCnt());
        assertEquals(4, trace.matchCnt());
        assertEquals(110L, trace.winnerAdId());
        assertEquals(new BigDecimal("1.01"), trace.payPrice());
        assertEquals(1, trace.priceMode());
        assertEquals(3, trace.candidates().size(), "两个失败候选 + 胜出候选");
        assertEquals("SUCCESS", trace.candidates().get(2).result());
        assertEquals(9, trace.timings().dbMs());
        assertEquals(12, trace.timings().redisMs());
        assertEquals(23, trace.timings().totalMs());
        assertEquals(3, trace.steps().size(), "检索/预热/成交（过滤后数量未变则不记步骤）");
    }

    @Test
    @DisplayName("兜底轨迹：记录原因代码与计费耗时")
    void fallbackTrace() {
        BidTraceBuilder builder = new BidTraceBuilder("req-fallback");
        builder.retrieved(2, 7);
        builder.matched(2);
        builder.fallback("BUDGET_EXHAUSTED", 15);

        BidTrace trace = builder.build(24);

        assertEquals("FALLBACK", trace.outcome());
        assertEquals("BUDGET_EXHAUSTED", trace.fallbackReason());
        assertNull(trace.winnerAdId());
        assertEquals(15, trace.timings().redisMs());
        assertTrue(trace.steps().get(trace.steps().size() - 1).detail().contains("BUDGET_EXHAUSTED"));
    }

    @Test
    @DisplayName("空广告轨迹：outcome=EMPTY，区别于返回兜底广告")
    void emptyTrace() {
        BidTraceBuilder builder = new BidTraceBuilder("req-empty");
        builder.retrieveFailed(800);
        builder.empty("DB_UNAVAILABLE", 0);

        BidTrace trace = builder.build(801);

        assertEquals("EMPTY", trace.outcome());
        assertEquals("DB_UNAVAILABLE", trace.fallbackReason());
        assertEquals(800, trace.timings().dbMs());
    }

    @Test
    @DisplayName("候选列表封顶 10 条，防止候选上限调大后轨迹无限膨胀")
    void candidateListIsCapped() {
        BidTraceBuilder builder = new BidTraceBuilder("req-cap");
        for (int i = 0; i < 25; i++) {
            builder.candidate(1000 + i, BigDecimal.ONE, new BigDecimal("0.99"), "INSUFFICIENT");
        }

        assertEquals(10, builder.build(1).candidates().size());
    }
}
