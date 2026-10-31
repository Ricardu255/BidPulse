package com.bidpulse.job;

/**
 * 一条预算对账差异。
 *
 * @param direction REDIS_AHEAD（有扣费没落账单） / DB_AHEAD（Redis 少记）
 * @param action    ALERT（只告警） / REPAIRED（已按账单修复） / SKIPPED（无法修复）
 */
public record ReconMismatch(
        Long adId,
        long redisCents,
        long dbCents,
        long diffCents,
        String direction,
        String action) {
}
