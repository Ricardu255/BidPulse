package com.bidpulse.bid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 二价定价的纯单元测试：不启动 Spring、不连 MySQL/Redis/Kafka。
 *
 * <p>这三条边界正是原实现最容易算错、却最难通过端到端测试覆盖的地方。
 */
class PricingPolicyTest {

    private static BigDecimal yuan(String v) {
        return new BigDecimal(v);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "期望 " + expected + "，实际 " + actual);
    }

    @Test
    @DisplayName("常规二价：次高价 + 最小加价")
    void secondPrice_usesNextBidPlusIncrement() {
        assertAmount("0.41", PricingPolicy.secondPrice(yuan("0.50"), yuan("0.40"), yuan("0.01")));
    }

    @Test
    @DisplayName("次高价 + 加价超过自身出价时封顶到自身出价")
    void secondPrice_neverExceedsWinnerBid() {
        assertAmount("0.50", PricingPolicy.secondPrice(yuan("0.50"), yuan("0.50"), yuan("0.01")));
    }

    @Test
    @DisplayName("唯一候选：次高按自身计算，成交价等于自身出价")
    void secondPrice_singleCandidateFallsBackToOwnBid() {
        assertAmount("1.20", PricingPolicy.secondPrice(yuan("1.20"), yuan("1.20"), yuan("0.01")));
    }

    @Test
    @DisplayName("入参为 null 直接拒绝，避免脏数据静默算出 0 元")
    void secondPrice_rejectsNull() {
        assertThrows(NullPointerException.class,
                () -> PricingPolicy.secondPrice(null, yuan("1.00"), yuan("0.01")));
        assertThrows(NullPointerException.class,
                () -> PricingPolicy.secondPrice(yuan("1.00"), null, yuan("0.01")));
        assertThrows(NullPointerException.class,
                () -> PricingPolicy.secondPrice(yuan("1.00"), yuan("0.50"), null));
    }
}
