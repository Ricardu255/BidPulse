package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.console.dto.DataView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台数据浏览查询：只允许白名单表，只支持少量等值过滤与 LIMIT。
 *
 * <p>不做通用 SQL 控制台：表名、列名、排序全部在服务端固定，过滤值走占位符，
 * 避免把数据库暴露给前端。
 *
 * <p>查询走控制台专用只读连接池，与竞价/消费的主池隔离；即使数据浏览被反复刷新，
 * 也最多占用 {@code bidpulse.console.read-db.max-pool-size} 个连接。
 *
 * <p>SQL 组装放在 query 层而不是 controller：controller 只负责 HTTP 契约，
 * 这样「哪些表可查、SQL 长什么样」可以被单测直接覆盖，也避免后续有人把
 * JdbcTemplate 注入到更多 controller 里。
 */
@Service
public class ConsoleDataQueryService {

    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public ConsoleDataQueryService(@Qualifier("consoleReadJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DataView query(String table, int limit, Long adId, String requestId) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        return switch (table) {
            case "bid_log" -> bidLog(cap, adId, requestId);
            case "billing_event" -> billingEvent(cap, adId);
            case "bid_stat_hourly" -> hourly(cap, adId);
            case "ad_freq" -> adFreq(cap, adId);
            case "processed_event" -> processedEvent(cap);
            default -> throw BizException.badRequest("不支持的数据表: " + table);
        };
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
