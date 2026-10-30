package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import com.bidpulse.infra.redis.BudgetService;
import com.bidpulse.console.dto.BudgetView;
import com.bidpulse.console.dto.FrequencyView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 控制台只读查询：把 Redis / MySQL 的原始状态整理成页面可直接展示的视图。
 *
 * <p>只读边界：这里不会播种预算、不会创建频次 key、不会回写广告状态。
 * 打开控制台不应该改变任何业务状态。
 */
@Service
@RequiredArgsConstructor
public class ConsoleQueryService {

    private final AdMapper adMapper;
    private final BudgetService budgetService;
    private final BidPulseProperties props;

    /** 某广告当天的预算与基础信息。 */
    public BudgetView budget(Long adId) {
        Ad ad = requireAd(adId);
        BudgetService.BudgetSnapshot snapshot = budgetService.snapshot(adId, ad.getDailyBudget());
        return new BudgetView(
                ad.getId(),
                ad.getTitle(),
                ad.getStatus(),
                ad.getBid(),
                ad.getDailyBudget(),
                snapshot.remainingCents(),
                snapshot.spentCents(),
                snapshot.ttlSeconds(),
                snapshot.seeded());
    }

    /**
     * 某用户当前小时对某广告的频次。
     *
     * <p>匿名流量没有稳定用户维度，直接返回 {@code enabled=false}，不读 Redis。
     */
    public FrequencyView frequency(String userId, Long adId) {
        Ad ad = requireAd(adId);
        int maxPerHour = Math.max(1, props.getFrequency().getMaxPerHour());
        if (userId == null || userId.isBlank() || !props.getFrequency().isEnabled()) {
            return new FrequencyView(userId, ad.getId(), false, null, maxPerHour, null);
        }
        BudgetService.FrequencySnapshot snapshot = budgetService.snapshotFrequency(userId, ad.getId());
        return new FrequencyView(
                userId,
                ad.getId(),
                true,
                snapshot.count(),
                snapshot.maxPerHour(),
                snapshot.ttlSeconds());
    }

    private Ad requireAd(Long adId) {
        if (adId == null) {
            throw BizException.badRequest("adId 不能为空");
        }
        Ad ad = adMapper.selectById(adId);
        if (ad == null) {
            throw BizException.notFound("广告不存在: " + adId);
        }
        return ad;
    }
}
