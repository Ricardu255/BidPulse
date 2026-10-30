package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.dto.BidRequest;
import com.bidpulse.bid.BidService;
import com.bidpulse.console.dto.RecentBidView;
import com.bidpulse.console.query.ConsoleOverviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/bids")
    public ApiResponse<BidRequest.Result> bid(@Valid @RequestBody BidRequest req) {
        long start = System.nanoTime();
        BidRequest.Result result = bidService.bid(req);

        ApiResponse<BidRequest.Result> response = ApiResponse.ok(result);
        response.setWallMs((int) ((System.nanoTime() - start) / 1_000_000));
        return response;
    }

    @GetMapping("/bids/recent")
    public ApiResponse<List<RecentBidView>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(overviewService.recentBids(limit));
    }
}
