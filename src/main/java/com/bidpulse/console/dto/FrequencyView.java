package com.bidpulse.console.dto;

/**
 * 频次面板视图。
 *
 * <p>{@code enabled=false} 表示当前请求不参与频次控制（匿名流量或功能关闭），
 * 此时 {@code count} 为 null，前端应展示“不控频”而不是 0。
 */
public record FrequencyView(
        String userId,
        Long adId,
        boolean enabled,
        Long count,
        int maxPerHour,
        Long ttlSeconds) {
}
