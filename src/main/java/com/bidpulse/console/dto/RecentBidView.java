package com.bidpulse.console.dto;

import java.math.BigDecimal;

/**
 * 最近竞价列表行。
 *
 * <p>{@code bidAt} 直接返回格式化后的字符串，避免前端处理 Jackson 日期格式差异。
 *
 * @param hasTrace 该竞价是否落有决策轨迹；true 时前端才展示「查看轨迹」入口
 */
public record RecentBidView(
        String requestId,
        Long adId,
        BigDecimal winBid,
        BigDecimal payPrice,
        boolean hasAd,
        int costMs,
        String bidAt,
        boolean hasTrace) {
}
