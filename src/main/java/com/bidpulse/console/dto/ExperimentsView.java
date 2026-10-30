package com.bidpulse.console.dto;

/**
 * 控制台实验开关快照。
 */
public record ExperimentsView(
        boolean fallbackEnabled,
        boolean fallbackForce,
        boolean frequencyEnabled,
        int frequencyMaxPerHour,
        boolean detailSyncInsert,
        boolean consoleAllowWrite) {
}
