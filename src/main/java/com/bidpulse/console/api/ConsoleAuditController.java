package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.audit.AuditLog;
import com.bidpulse.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 审计日志只读查询；SecurityConfig 限制为 ADMIN。 */
@RestController
@RequestMapping("/console-api/v1/audit")
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ConsoleAuditController {

    private final AuditService auditService;

    @GetMapping
    public ApiResponse<List<AuditLog>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(auditService.recent(limit));
    }
}
