package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.BudgetView;
import com.bidpulse.console.dto.FrequencyView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 控制台查询的纯单测：不启动 Spring、不连 Redis/MySQL。
 *
 * <p>重点锁住两条边界：未知广告必须 404；匿名 userId 不读 Redis。
 */
@ExtendWith(MockitoExtension.class)
class ConsoleQueryServiceTest {

    @Mock
    private AdMapper adMapper;

    @Mock
    private BudgetService budgetService;

    private ConsoleQueryService service;

    @BeforeEach
    void setUp() {
        BidPulseProperties props = new BidPulseProperties();
        props.getFrequency().setEnabled(true);
        props.getFrequency().setMaxPerHour(3);
        service = new ConsoleQueryService(adMapper, budgetService, props);
    }

    @Test
    @DisplayName("预算视图映射 Redis 快照与广告基础信息")
    void budgetMapsSnapshot() {
        Ad ad = ad(110L);
        when(adMapper.selectById(110L)).thenReturn(ad);
        when(budgetService.snapshot(110L, ad.getDailyBudget()))
                .thenReturn(new BudgetService.BudgetSnapshot(9900L, 100L, 172800L, true));

        BudgetView view = service.budget(110L);

        assertEquals(110L, view.adId());
        assertEquals("创意标题-110", view.title());
        assertEquals(1, view.status());
        assertEquals(0, new BigDecimal("1000.00").compareTo(view.dailyBudget()));
        assertEquals(9900L, view.remainingCents());
        assertEquals(100L, view.spentCents());
        assertEquals(172800L, view.ttlSeconds());
        assertTrue(view.seeded());
    }

    @Test
    @DisplayName("匿名 userId 不读 Redis，直接返回不控频")
    void anonymousSkipsRedis() {
        when(adMapper.selectById(110L)).thenReturn(ad(110L));

        FrequencyView view = service.frequency("  ", 110L);

        assertFalse(view.enabled());
        assertNull(view.count());
        assertEquals(3, view.maxPerHour());
        assertNull(view.ttlSeconds());
        verifyNoInteractions(budgetService);
    }

    @Test
    @DisplayName("有 userId 时返回当前小时计数")
    void frequencyMapsSnapshot() {
        when(adMapper.selectById(110L)).thenReturn(ad(110L));
        when(budgetService.snapshotFrequency("learner-1", 110L))
                .thenReturn(new BudgetService.FrequencySnapshot(2L, 3, 7100L));

        FrequencyView view = service.frequency("learner-1", 110L);

        assertTrue(view.enabled());
        assertEquals(2L, view.count());
        assertEquals(3, view.maxPerHour());
        assertEquals(7100L, view.ttlSeconds());
    }

    @Test
    @DisplayName("广告不存在时返回 404")
    void missingAdReturns404() {
        when(adMapper.selectById(999L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.budget(999L));
        assertEquals(404, e.getStatus());
    }

    private static Ad ad(Long id) {
        Ad ad = new Ad();
        ad.setId(id);
        ad.setTitle("创意标题-" + id);
        ad.setStatus(1);
        ad.setBid(new BigDecimal("1.5000"));
        ad.setDailyBudget(new BigDecimal("1000.00"));
        return ad;
    }
}
