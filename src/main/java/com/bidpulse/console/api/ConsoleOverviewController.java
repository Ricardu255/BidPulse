package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.console.query.ConsoleOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览页聚合接口。
 */
@RestController
@RequestMapping("/console-api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleOverviewController {

    private final ConsoleOverviewService overviewService;

    @GetMapping("/overview")
    public ApiResponse<OverviewView> overview() {
        return ApiResponse.ok(overviewService.overview());
    }
}
