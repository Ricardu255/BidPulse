package com.bidpulse.console.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 概览页聚合视图。
 *
 * <p>{@code fallbackRate} 是 0~1 的小数，由前端格式化成百分比；
 * {@code health} 的 key 是 Spring Boot 健康组件名（db/redis/kafka/...），
 * value 是 {@code UP/DOWN/UNKNOWN}。
 */
public record OverviewView(
        long bidsToday,
        long fallbackToday,
        double fallbackRate,
        BigDecimal spendToday,
        long p95CostMs,
        long totalAds,
        long activeAds,
        long pausedAds,
        long exhaustedAds,
        long kafkaLag,
        Map<String, String> health,
        List<TrendPoint> trend) {
}
