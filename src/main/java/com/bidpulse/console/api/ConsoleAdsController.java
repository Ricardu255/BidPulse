package com.bidpulse.console.api;

import com.bidpulse.admin.AdAdminService;
import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.api.PageResult;
import com.bidpulse.api.dto.AdQuery;
import com.bidpulse.api.dto.AdUpsertRequest;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 控制台广告与投放管理。
 *
 * <p>不重复实现业务逻辑：列表/详情/状态/预算全部复用 {@link AdAdminService}，
 * 保证和 {@code /admin/ad} 的行为一致（包括日预算变更后同步当天 Redis）。
 */
@RestController
@RequestMapping("/console-api/v1/ads")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleAdsController {

    private static final BigDecimal MIN_DAILY_BUDGET = new BigDecimal("1.00");

    private final AdAdminService adAdminService;
    private final AdMapper adMapper;
    private final BidPulseProperties props;

    @GetMapping
    public ApiResponse<PageResult<Ad>> page(@Valid AdQuery query) {
        return ApiResponse.ok(adAdminService.page(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(adAdminService.detail(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> changeStatus(@PathVariable Long id,
                                                         @RequestParam Integer status) {
        requireWrite();
        adAdminService.changeStatus(id, status);
        return ApiResponse.ok(Map.of("id", id, "status", status));
    }

    /**
     * 只调日预算：读取现有广告拼一个全量更新请求，targets 传 null 表示不动定向。
     * 日预算变化会由 {@link AdAdminService#update} 在事务提交后同步到当天 Redis。
     */
    @PutMapping("/{id}/budget")
    public ApiResponse<Map<String, Object>> updateBudget(@PathVariable Long id,
                                                         @RequestParam BigDecimal dailyBudget) {
        requireWrite();
        if (dailyBudget == null || dailyBudget.compareTo(MIN_DAILY_BUDGET) < 0) {
            throw BizException.badRequest("dailyBudget 应不小于 1.00 元");
        }
        Ad ad = adMapper.selectById(id);
        if (ad == null) {
            throw BizException.notFound("广告不存在: " + id);
        }

        AdUpsertRequest req = new AdUpsertRequest();
        req.setTitle(ad.getTitle());
        req.setCreativeUrl(ad.getCreativeUrl());
        req.setCategoryCode(ad.getCategoryCode());
        req.setBid(ad.getBid());
        req.setDailyBudget(dailyBudget);
        req.setTargets(null);
        adAdminService.update(id, req);

        return ApiResponse.ok(Map.of("id", id, "dailyBudget", dailyBudget));
    }

    private void requireWrite() {
        if (!props.getConsole().isAllowWrite()) {
            throw BizException.forbidden("控制台写操作已关闭（bidpulse.console.allow-write=false）");
        }
    }
}
