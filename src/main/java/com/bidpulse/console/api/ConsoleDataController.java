package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.console.dto.DataView;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 控制台数据浏览：只允许白名单表，只支持少量等值过滤与 LIMIT。
 *
 * <p>不做通用 SQL 控制台：表名、列名、排序全部在服务端固定，过滤值走占位符，
 * 避免把数据库暴露给前端。
 */
@RestController
@RequestMapping("/console-api/v1/data")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleDataController {

    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    @GetMapping("/{table}")
    public ApiResponse<DataView> query(@PathVariable String table,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) Long adId,
                                       @RequestParam(required = false) String requestId) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ApiResponse.ok(switch (table) {
            case "bid_log" -> bidLog(cap, adId, requestId);
            case "billing_event" -> billingEvent(cap, adId);
            case "bid_stat_hourly" -> hourly(cap, adId);
            case "ad_freq" -> adFreq(cap, adId);
            case "processed_event" -> processedEvent(cap);
            default -> throw BizException.badRequest("不支持的数据表: " + table);
        });
    }

    private DataView bidLog(int limit, Long adId, String requestId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT request_id, ad_id, win_bid, pay_price, has_ad, cost_ms, "
                        + "DATE_FORMAT(bid_at, '%Y-%m-%d %H:%i:%s') AS bid_at FROM bid_log WHERE 1=1");
        if (adId != null) {
            sql.append(" AND ad_id = ?");
            args.add(adId);
        }
        if (requestId != null && !requestId.isBlank()) {
            sql.append(" AND request_id = ?");
            args.add(requestId.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return new DataView("bid_log",
                List.of("request_id", "ad_id", "win_bid", "pay_price", "has_ad", "cost_ms", "bid_at"),
                jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private DataView billingEvent(int limit, Long adId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT event_id, ad_id, advertiser_id, pay_price, ts, "
                        + "FROM_UNIXTIME(ts / 1000) AS event_time FROM billing_event WHERE 1=1");
        if (adId != null) {
            sql.append(" AND ad_id = ?");
            args.add(adId);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(limit);
        return new DataView("billing_event",
                List.of("event_id", "ad_id", "advertiser_id", "pay_price", "ts", "event_time"),
                jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private DataView hourly(int limit, Long adId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT stat_hour, ad_id, impressions, spend FROM bid_stat_hourly WHERE 1=1");
        if (adId != null) {
            sql.append(" AND ad_id = ?");
            args.add(adId);
        }
        sql.append(" ORDER BY stat_hour DESC LIMIT ?");
        args.add(limit);
        return new DataView("bid_stat_hourly",
                List.of("stat_hour", "ad_id", "impressions", "spend"),
                jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private DataView adFreq(int limit, Long adId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ad_id, cnt FROM ad_freq WHERE 1=1");
        if (adId != null) {
            sql.append(" AND ad_id = ?");
            args.add(adId);
        }
        sql.append(" ORDER BY cnt DESC LIMIT ?");
        args.add(limit);
        return new DataView("ad_freq",
                List.of("ad_id", "cnt"),
                jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private DataView processedEvent(int limit) {
        return new DataView("processed_event",
                List.of("event_id", "grp", "created_at"),
                jdbc.queryForList(
                        "SELECT event_id, grp, created_at FROM processed_event "
                                + "ORDER BY created_at DESC LIMIT ?", limit));
    }
}
