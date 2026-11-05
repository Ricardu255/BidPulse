package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.console.dto.DataView;
import com.bidpulse.console.query.ConsoleDataQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台数据浏览入口：只做 HTTP 参数接收与响应包装。
 *
 * <p>白名单、SQL 组装与只读池都在 {@link ConsoleDataQueryService}；
 * controller 不直接依赖 JdbcTemplate，避免 SQL 逐渐散落到 HTTP 层。
 */
@RestController
@RequestMapping("/console-api/v1/data")
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleDataController {

    private final ConsoleDataQueryService dataQueryService;

    public ConsoleDataController(ConsoleDataQueryService dataQueryService) {
        this.dataQueryService = dataQueryService;
    }

    @GetMapping("/{table}")
    public ApiResponse<DataView> query(@PathVariable String table,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) Long adId,
                                       @RequestParam(required = false) String requestId) {
        return ApiResponse.ok(dataQueryService.query(table, limit, adId, requestId));
    }
}
