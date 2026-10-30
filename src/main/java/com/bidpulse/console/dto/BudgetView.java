package com.bidpulse.console.dto;

import java.math.BigDecimal;

/**
 * 预算面板视图：广告基础信息 + Redis 当日预算快照。
 *
 * <p>金额同时给出元（{@code bid}/{@code dailyBudget}）和分（{@code remainingCents}/
 * {@code spentCents}），前端按分格式化，避免浮点误差。
 */
public record BudgetView(
        Long adId,
        String title,
        Integer status,
        BigDecimal bid,
        BigDecimal dailyBudget,
        Long remainingCents,
        Long spentCents,
        Long ttlSeconds,
        boolean seeded) {
}
