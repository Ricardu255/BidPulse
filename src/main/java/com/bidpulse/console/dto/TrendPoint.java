package com.bidpulse.console.dto;

/**
 * 近 30 分钟按分钟聚合的请求趋势。
 */
public record TrendPoint(String minute, long bids, long fallback) {
}
