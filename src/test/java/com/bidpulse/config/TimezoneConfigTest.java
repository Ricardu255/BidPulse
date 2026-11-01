package com.bidpulse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试 JVM 时区守卫。
 *
 * <p>预算日 key、频次小时 key、概览“今日”窗口都按 {@link ZoneId#systemDefault()} 切分；
 * 如果 CI 或本机退回 UTC，零点附近会写到错误的日期分片。Surefire 已固定
 * {@code -Duser.timezone=Asia/Shanghai}，CI 另设 {@code TZ=Asia/Shanghai}。
 */
class TimezoneConfigTest {

    @Test
    @DisplayName("测试 JVM 固定为 Asia/Shanghai")
    void testJvmPinnedToShanghai() {
        assertEquals("Asia/Shanghai", ZoneId.systemDefault().getId());
    }

    @Test
    @DisplayName("东八区零点对应 UTC 前一日 16:00")
    void dayBoundaryIsUtcPlusEight() {
        Instant start = LocalDate.of(2026, 1, 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();

        assertEquals(Instant.parse("2025-12-31T16:00:00Z"), start);
    }
}
