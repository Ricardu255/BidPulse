package com.bidpulse.admin;

import com.bidpulse.api.BizException;
import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import com.bidpulse.infra.redis.BudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdAdminService 的纯单测，重点锁住“只改日预算”不覆盖其他字段。
 */
@ExtendWith(MockitoExtension.class)
class AdAdminServiceTest {

    @Mock
    private AdMapper adMapper;
    @Mock
    private BudgetService budgetService;

    private AdAdminService service;

    @BeforeEach
    void setUp() {
        service = new AdAdminService(adMapper, budgetService);
    }

    @Test
    @DisplayName("只调日预算：走单列更新，不整行 updateAd，并同步 Redis")
    void updateDailyBudget_updatesOnlyBudgetAndSyncsRedis() {
        Ad ad = ad(1L, new BigDecimal("1000.00"));
        when(adMapper.selectById(1L)).thenReturn(ad);
        when(adMapper.updateDailyBudget(1L, new BigDecimal("200.00"))).thenReturn(1);

        service.updateDailyBudget(1L, new BigDecimal("200.00"));

        verify(adMapper).updateDailyBudget(1L, new BigDecimal("200.00"));
        verify(adMapper, never()).updateAd(any());
        verify(budgetService).applyDailyBudget(1L, new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("预算相同则不写 DB、不写 Redis")
    void updateDailyBudget_sameValueIsNoop() {
        when(adMapper.selectById(1L)).thenReturn(ad(1L, new BigDecimal("1000.00")));

        service.updateDailyBudget(1L, new BigDecimal("1000.00"));

        verify(adMapper, never()).updateDailyBudget(any(), any());
        verifyNoInteractions(budgetService);
    }

    @Test
    @DisplayName("广告不存在返回 404")
    void updateDailyBudget_missingAdReturns404() {
        when(adMapper.selectById(9L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateDailyBudget(9L, new BigDecimal("200.00")));
        assertEquals(404, e.getStatus());
    }

    @Test
    @DisplayName("预算低于 1 元返回 400，且不查库")
    void updateDailyBudget_tooLowReturns400() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateDailyBudget(1L, new BigDecimal("0.99")));
        assertEquals(400, e.getStatus());
        verifyNoInteractions(adMapper, budgetService);
    }

    private static Ad ad(Long id, BigDecimal dailyBudget) {
        Ad ad = new Ad();
        ad.setId(id);
        ad.setTitle("ad");
        ad.setDailyBudget(dailyBudget);
        return ad;
    }
}
