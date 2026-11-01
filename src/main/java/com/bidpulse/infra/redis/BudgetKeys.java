package com.bidpulse.infra.redis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 预算/频次 Redis key 的唯一命名入口。
 *
 * <p>预算按自然日分桶：{@code budget:{adId}:{yyyyMMdd}} / {@code spent:{adId}:{yyyyMMdd}}；
 * 频次按「用户 + 自然小时」分窗：{@code freq:u:{userId}:{yyyyMMddHH}}。
 * 所有读写路径都必须走这里，避免 key 格式散落后出现“写入一个 key、读取另一个 key”。
 */
final class BudgetKeys {

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private BudgetKeys() {
    }

    static String budgetKey(Long adId, String date) {
        return "budget:" + adId + ":" + date;
    }

    static String spentKey(Long adId, String date) {
        return "spent:" + adId + ":" + date;
    }

    static List<String> keys(Long adId, LocalDate date) {
        String d = date.format(DATE_FMT);
        return Arrays.asList(budgetKey(adId, d), spentKey(adId, d));
    }

    static String freqKey(String userId, LocalDateTime time) {
        return "freq:u:" + userId + ":" + time.format(HOUR_FMT);
    }
}
