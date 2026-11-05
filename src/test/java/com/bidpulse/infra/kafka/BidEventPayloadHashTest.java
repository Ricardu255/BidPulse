package com.bidpulse.infra.kafka;

import com.bidpulse.event.BidEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 载荷指纹的稳定性契约。
 *
 * <p>这组用例防的是两类会直接造成资损的误判：
 * <ul>
 *   <li><b>误报冲突</b>：同一事件的 JSON 只是金额写法不同（{@code 0.0200} vs {@code 0.02}），
 *       若指纹不同，正常重投会被打进 DLT；</li>
 *   <li><b>漏报冲突</b>：不同载荷编码成同一串，迟到的重放会绕过冲突检测改写金额。</li>
 * </ul>
 */
class BidEventPayloadHashTest {

    @Test
    @DisplayName("同一事件重复计算得到相同的 64 位十六进制指纹")
    void isDeterministic() {
        BidEvent event = sample();

        String first = BidEventPayloadHash.sha256Hex(event);
        String second = BidEventPayloadHash.sha256Hex(event);

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("金额只比较数值：1.20 与 1.2、0.0200 与 0.02 指纹相同")
    void normalizesBigDecimalScale() {
        BidEvent a = sample();
        a.setWinBid(new BigDecimal("1.20"));
        a.setPayPrice(new BigDecimal("0.0200"));

        BidEvent b = sample();
        b.setWinBid(new BigDecimal("1.2"));
        b.setPayPrice(new BigDecimal("0.02"));

        assertEquals(BidEventPayloadHash.sha256Hex(a), BidEventPayloadHash.sha256Hex(b));
    }

    @Test
    @DisplayName("0 与 0.00 指纹相同，0 与 0.01 指纹不同")
    void distinguishesZeroAmounts() {
        BidEvent zero = sample();
        zero.setPayPrice(new BigDecimal("0"));
        BidEvent zeroScaled = sample();
        zeroScaled.setPayPrice(new BigDecimal("0.00"));
        BidEvent nonzero = sample();
        nonzero.setPayPrice(new BigDecimal("0.01"));

        assertEquals(BidEventPayloadHash.sha256Hex(zero), BidEventPayloadHash.sha256Hex(zeroScaled));
        assertNotEquals(BidEventPayloadHash.sha256Hex(zero), BidEventPayloadHash.sha256Hex(nonzero));
    }

    @Test
    @DisplayName("null 与空串语义不同")
    void distinguishesNullFromEmptyString() {
        BidEvent nullRegion = sample();
        nullRegion.setRegion(null);
        BidEvent emptyRegion = sample();
        emptyRegion.setRegion("");

        assertNotEquals(BidEventPayloadHash.sha256Hex(nullRegion),
                BidEventPayloadHash.sha256Hex(emptyRegion));
    }

    @Test
    @DisplayName("字段边界无歧义：ab + 空串 与 a + b 指纹不同")
    void stringLengthPrefixPreventsAmbiguity() {
        BidEvent first = sample();
        first.setEventId("ab");
        first.setRequestId("");
        BidEvent second = sample();
        second.setEventId("a");
        second.setRequestId("b");

        assertNotEquals(BidEventPayloadHash.sha256Hex(first),
                BidEventPayloadHash.sha256Hex(second));
    }

    @Test
    @DisplayName("任何业务字段变化都会改变指纹")
    void anyBusinessFieldChangeChangesHash() {
        String base = BidEventPayloadHash.sha256Hex(sample());

        assertNotEquals(base, hashOf(event -> event.setEventId("evt-other")));
        assertNotEquals(base, hashOf(event -> event.setRequestId("req-other")));
        assertNotEquals(base, hashOf(event -> event.setTs(event.getTs() + 1)));
        assertNotEquals(base, hashOf(event -> event.setRegion("other-region")));
        assertNotEquals(base, hashOf(event -> event.setUserId("user-other")));
        assertNotEquals(base, hashOf(event -> event.setAdId(456L)));
        assertNotEquals(base, hashOf(event -> event.setAdvertiserId(99)));
        assertNotEquals(base, hashOf(event -> event.setWinBid(new BigDecimal("2.00"))));
        assertNotEquals(base, hashOf(event -> event.setPayPrice(new BigDecimal("0.03"))));
        assertNotEquals(base, hashOf(event -> event.setFallback(true)));
        assertNotEquals(base, hashOf(event -> event.setHasAd(false)));
        assertNotEquals(base, hashOf(event -> event.setPriceMode(0)));
    }

    private static String hashOf(java.util.function.Consumer<BidEvent> mutation) {
        BidEvent event = sample();
        mutation.accept(event);
        return BidEventPayloadHash.sha256Hex(event);
    }

    private static BidEvent sample() {
        return BidEvent.builder()
                .eventId("evt-1")
                .requestId("req-1")
                .ts(1_760_000_000_000L)
                .region("440318")
                .userId("user-1")
                .adId(123L)
                .advertiserId(11)
                .winBid(new BigDecimal("1.50"))
                .payPrice(new BigDecimal("0.02"))
                .fallback(false)
                .hasAd(true)
                .priceMode(1)
                .build();
    }
}
