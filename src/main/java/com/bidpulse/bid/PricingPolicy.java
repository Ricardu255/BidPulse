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

    /**
     * 计算一个候选在二价中的「有效出价」：{@code min(出价, 当日剩余预算)}。
     *
     * <p>为什么需要它：候选出价再高，当天预算不够也承接不了这次竞价。直接用出价参与二价
     * 会把成交价抬到高于真实机会成本；而「付不起就整条跳过」又会让价格在预算边界上断崖。
     * 用有效出价参与比较，价格随预算连续变化。
     *
     * @param bid       候选出价（元）
     * @param remaining 当日剩余预算（元）
     * @return 有效出价，恒在 {@code [0, bid]} 区间内
     */
    public static BigDecimal effectiveBid(BigDecimal bid, BigDecimal remaining) {
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(remaining, "remaining");
        return bid.min(remaining.max(BigDecimal.ZERO));
    }
}
