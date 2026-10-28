package com.bidpulse.bid;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 二价（Vickrey）定价策略。
 *
 * <p><b>为什么单独成类</b>：这是全项目唯一「用三行算术决定收多少钱」的地方，
 * 却原来埋在 {@code BidService} 的计费循环里，只能靠端到端测试间接覆盖。
 * 抽成无状态纯函数后，可以直接对边界做单元测试：
 * 唯一候选、次高价高于自身、加价后超过自身出价。
 *
 * <p>公式：{@code 成交价 = min(胜出者出价, 次高出价 + 最小加价)}。
 * 对广告主友好——只比对手多付一个最小加价，不会按自己的最高意愿出价。
 */
public final class PricingPolicy {

    private PricingPolicy() {
    }

    /**
     * 计算一次二价成交价。
     *
     * @param winnerBid    胜出候选的出价（元）
     * @param nextBid      次高候选的出价（元）；只有一个候选时传胜出者自己的出价
     * @param minIncrement 最小加价（元）
     * @return 实际成交价，恒不超过 {@code winnerBid}
     */
    public static BigDecimal secondPrice(BigDecimal winnerBid, BigDecimal nextBid, BigDecimal minIncrement) {
        Objects.requireNonNull(winnerBid, "winnerBid");
        Objects.requireNonNull(nextBid, "nextBid");
        Objects.requireNonNull(minIncrement, "minIncrement");
        return nextBid.add(minIncrement).min(winnerBid);
    }
}
