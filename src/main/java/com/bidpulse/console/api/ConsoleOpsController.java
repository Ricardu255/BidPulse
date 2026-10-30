package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.DltMessageView;
import com.bidpulse.console.query.DltQueryService;
import com.bidpulse.job.BudgetReconJob;
import com.bidpulse.job.DltReplayJob;
import com.bidpulse.job.ReconReportStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台异步运维：DLT 浏览/重放、预算对账状态与手动触发。
 */
@RestController
@RequestMapping("/console-api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleOpsController {

    private final DltQueryService dltQueryService;
    private final DltReplayJob dltReplayJob;
    private final BudgetReconJob budgetReconJob;
    private final ReconReportStore reconReportStore;
    private final BidPulseProperties props;

    @GetMapping("/dlt/messages")
    public ApiResponse<List<DltMessageView>> dltMessages(@RequestParam(defaultValue = "20") int limit) {
        try {
            return ApiResponse.ok(dltQueryService.messages(limit));
        } catch (Exception e) {
            throw BizException.unavailable("浏览 DLT 消息失败: " + e.getMessage());
        }
    }

    @PostMapping("/dlt/replay")
    public ApiResponse<DltReplayJob.ReplayStats> replay(@RequestParam(defaultValue = "100") int maxRecords) {
        requireWrite();
        int cap = Math.max(1, Math.min(maxRecords, 1000));
        try {
            return ApiResponse.ok(dltReplayJob.replayOnce(cap));
        } catch (Exception e) {
            throw BizException.unavailable("DLT 重放失败: " + e.getMessage());
        }
    }

    @GetMapping("/recon")
    public ApiResponse<ReconReportStore.ReconReport> recon() {
        return ApiResponse.ok(reconReportStore.last());
    }

    @PostMapping("/recon/run")
    public ApiResponse<ReconReportStore.ReconReport> runRecon() {
        requireWrite();
        try {
            budgetReconJob.runOnce();
            return ApiResponse.ok(reconReportStore.last());
        } catch (Exception e) {
            throw BizException.unavailable("预算对账失败: " + e.getMessage());
        }
    }

    private void requireWrite() {
        if (!props.getConsole().isAllowWrite()) {
            throw BizException.forbidden("控制台写操作已关闭（bidpulse.console.allow-write=false）");
        }
    }
}
