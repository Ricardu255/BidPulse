package com.bidpulse.infra.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BudgetAmountsTest {

    @Test
    @DisplayName("元转分向下取整，不四舍五入")
    void toCentsRoundsDown() {
        assertEquals(101L, BudgetAmounts.toCents(new BigDecimal("1.019")));
        assertEquals(100L, BudgetAmounts.toCents(new BigDecimal("1.00")));
        assertEquals(0L, BudgetAmounts.toCents(new BigDecimal("0.009")));
    }

    @Test
    @DisplayName("分转元保留两位小数")
    void parsesCentsToYuan() {
        assertEquals(new BigDecimal("1.01"), BudgetAmounts.parse("101"));
        assertEquals(new BigDecimal("0.00"), BudgetAmounts.parse("0"));
    }

    @Test
    @DisplayName("脏值返回 null，不把异常抛给展示层")
    void invalidCentsReturnNull() {
        assertNull(BudgetAmounts.parseCentsOrNull(null));
        assertNull(BudgetAmounts.parseCentsOrNull("abc"));
        assertEquals(42L, BudgetAmounts.parseCentsOrNull(" 42 "));
    }
}
