package com.bidpulse.infra.redis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 预算金额与 Redis 计数值的换算；只做纯函数，方便单测与复用。 */
final class BudgetAmounts {

    private BudgetAmounts() {
    }

    /**
     * 金额统一以「分」为单位存进 Redis，避免 double 的浮点误差。
     * 必须显式向下取整：{@link BigDecimal#longValue()} 会直接截断小数部分，
     * 而 {@code ad.bid} 是 DECIMAL(10,4)、二价成交价可能带 4 位小数。
     */
    static long toCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /** 分 -> 元 */
    static BigDecimal parse(String cents) {
        return new BigDecimal(cents).movePointLeft(2);
    }

    /** 只读取整数值（分 / 计数）；缺失或脏值返回 null，由调用方决定如何展示。 */
    static Long parseCentsOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
