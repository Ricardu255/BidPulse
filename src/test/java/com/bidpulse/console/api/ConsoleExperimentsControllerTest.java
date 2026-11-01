package com.bidpulse.console.api;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验开关的原子性：任何字段非法时，所有字段都不能生效。
 */
class ConsoleExperimentsControllerTest {

    private BidPulseProperties props;
    private ConsoleExperimentsController controller;

    @BeforeEach
    void setUp() {
        props = new BidPulseProperties();
        props.getConsole().setAllowWrite(true);
        controller = new ConsoleExperimentsController(props);
    }

    @Test
    @DisplayName("非法频次上限返回 400，前面的兜底开关不得被部分修改")
    void invalidUpdateDoesNotPartiallyApply() {
        BizException e = assertThrows(BizException.class, () -> controller.update(
                new ConsoleExperimentsController.ExperimentsUpdate(false, true, false, 0)));

        assertEquals(400, e.getStatus());
        assertTrue(props.getFallback().isEnabled());
        assertFalse(props.getFallback().isForce());
        assertTrue(props.getFrequency().isEnabled());
        assertEquals(3, props.getFrequency().getMaxPerHour());
    }

    @Test
    @DisplayName("合法更新一次性生效并返回快照")
    void validUpdateAppliesAllFields() {
        var view = controller.update(
                new ConsoleExperimentsController.ExperimentsUpdate(false, true, false, 7)).getData();

        assertFalse(view.fallbackEnabled());
        assertTrue(view.fallbackForce());
        assertFalse(view.frequencyEnabled());
        assertEquals(7, view.frequencyMaxPerHour());
    }
}
