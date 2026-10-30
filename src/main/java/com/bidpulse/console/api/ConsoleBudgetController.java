package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.console.dto.BudgetView;
import com.bidpulse.console.dto.FrequencyView;
import com.bidpulse.console.query.ConsoleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台预算 / 频次只读查询。
 */
@RestController
@RequestMapping("/console-api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleBudgetController {

    private final ConsoleQueryService queryService;

    @GetMapping("/budget")
    public ApiResponse<BudgetView> budget(@RequestParam Long adId) {
        return ApiResponse.ok(queryService.budget(adId));
    }

    @GetMapping("/frequency")
    public ApiResponse<FrequencyView> frequency(@RequestParam Long adId,
                                                @RequestParam(required = false) String userId) {
        return ApiResponse.ok(queryService.frequency(userId, adId));
    }
}
