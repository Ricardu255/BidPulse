package com.bidpulse.infra.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetKeysTest {

    @Test
    @DisplayName("预算 key 按广告 + 自然日分桶，budget/spent 成对")
    void budgetKeysUseSameDateBucket() {
        LocalDate date = LocalDate.of(2026, 11, 1);

        assertEquals("budget:110:20261101", BudgetKeys.budgetKey(110L, "20261101"));
        assertEquals("spent:110:20261101", BudgetKeys.spentKey(110L, "20261101"));
        assertEquals(List.of("budget:110:20261101", "spent:110:20261101"),
                BudgetKeys.keys(110L, date));
    }

    @Test
    @DisplayName("频次 key 按用户 + 自然小时分窗")
    void frequencyKeyUsesUserAndHour() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 1, 9, 30);

        assertEquals("freq:u:learner-1:2026110109", BudgetKeys.freqKey("learner-1", time));
    }
}
