package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.dto.BidRequest;
import com.bidpulse.api.dto.BidTrace;
import com.bidpulse.bid.BidService;
import com.bidpulse.console.dto.RecentBidView;
import com.bidpulse.console.query.ConsoleBidTraceService;
import com.bidpulse.console.query.ConsoleOverviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台竞价入口。
 *
 * <p>与 {@code POST /bid} 走同一个 {@link BidService}，只是换了版本化前缀，
 * 方便控制台后续在不影响外部流量接口的前提下增加调试字段。
 */
@RestController
@RequestMapping("/console-api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleBidController {

    private final BidService bidService;
    private final ConsoleOverviewService overviewService;
    private final ConsoleBidTraceService bidTraceService;

    /**
     * 控制台竞价：与外部 {@code /bid} 走同一条链路，但采集决策轨迹。
     * 轨迹随应答返回一份，同时异步落 {@code bid_log.trace_json} 供事后回看。
     */
    @PostMapping("/bids")
    public ApiResponse<BidRequest.Result> bid(@Valid @RequestBody BidRequest req) {
        long start = System.nanoTime();
        BidRequest.Result result = bidService.bid(req, true);

        ApiResponse<BidRequest.Result> response = ApiResponse.ok(result);
        response.setWallMs((int) ((System.nanoTime() - start) / 1_000_000));
        return response;
    }

    @GetMapping("/bids/recent")
    public ApiResponse<List<RecentBidView>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(overviewService.recentBids(limit));
    }

    /**
     * 回看一次竞价的决策轨迹。
     *
     * <p>明细异步落库，控制台刚发起的竞价可能短暂 404，前端应做一次延迟重试。
     */
    @GetMapping("/bids/{requestId}/trace")
    public ApiResponse<BidTrace> trace(@PathVariable String requestId) {
        return ApiResponse.ok(bidTraceService.trace(requestId));
    }
}
