package com.bidpulse.console.query;

import com.bidpulse.bid.FallbackAdProvider;
import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.infra.kafka.KafkaLagQueryService;
import com.bidpulse.console.dto.RecentBidView;
import com.bidpulse.console.dto.TrendPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 概览页与最近竞价的只读查询。
 *
 * <p>数据来源：
 * <ul>
 *   <li>{@code bid_log}：今日请求数、兜底数、P95、近 30 分钟趋势、最近竞价；</li>
 *   <li>{@code billing_event}：今日消耗（异步账单，允许短暂落后于实时扣减）；</li>
 *   <li>{@code ad}：广告状态分布；</li>
 *   <li>Kafka AdminClient：消费组总 lag；</li>
 *   <li>Actuator：DB / Redis / Kafka 健康状态。</li>
 * </ul>
 *
 * <p><b>快照一致性</b>：概览里的 5 个数据库查询原本各自自动提交，页面可能看到
 * 「请求数已包含刚成交的一单，消耗却还没算上」的撕裂值。现在它们被放进同一个
 * REPEATABLE_READ 只读事务（{@code consoleReadTransactionTemplate}），共享一个
 * 事务快照；Kafka lag 与健康检查在事务外执行，避免外部 RPC 把只读事务拖长。
 *
 * <p><b>连接隔离</b>：所有查询走控制台专用只读连接池（{@code consoleReadJdbcTemplate}），
 * 不再与竞价/消费共享主池。
 */
@Slf4j
@Service
public class ConsoleOverviewService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshotTx;
    private final KafkaLagQueryService kafkaLagQueryService;
    private final HealthEndpoint healthEndpoint;
    private final BreakerStatusProvider breakerStatusProvider;

    public ConsoleOverviewService(
            @Qualifier("consoleReadJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("consoleReadTransactionTemplate") TransactionTemplate snapshotTx,
            KafkaLagQueryService kafkaLagQueryService,
            HealthEndpoint healthEndpoint,
            BreakerStatusProvider breakerStatusProvider) {
        this.jdbc = jdbc;
        this.snapshotTx = snapshotTx;
        this.kafkaLagQueryService = kafkaLagQueryService;
        this.healthEndpoint = healthEndpoint;
        this.breakerStatusProvider = breakerStatusProvider;
    }

    public OverviewView overview() {
        // 统一用 JVM 本地时区计算窗口，避免 MySQL 容器 UTC 与宿主 Asia/Shanghai 混用。
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime dayStart = LocalDate.now(zone).atStartOfDay();
        long dayStartEpochMillis = dayStart.atZone(zone).toInstant().toEpochMilli();
        LocalDateTime trendFrom = LocalDateTime.now(zone).minusMinutes(30);

        Snapshot snapshot = snapshotTx.execute(
                status -> loadSnapshot(dayStart, dayStartEpochMillis, trendFrom));
        if (snapshot == null) {
            throw new IllegalStateException("概览快照事务不应返回 null");
        }

        // Kafka lag / 健康检查放到事务外：AdminClient 最长等 5s，健康检查也可能超时，
        // 让它们参与事务只会延长只读连接占用时间。
        long kafkaLag;
        try {
            kafkaLag = kafkaLagQueryService.totalLag();
        } catch (Exception e) {
            log.warn("概览查询 Kafka lag 失败: {}", e.getMessage());
            kafkaLag = -1;
        }

        Map<String, String> health = health();
        health.putAll(breakerStatusProvider.states());

        long[] bids = snapshot.bids();
        long[] ads = snapshot.ads();
        double fallbackRate = bids[0] == 0 ? 0.0 : (double) bids[1] / bids[0];
        return new OverviewView(
                bids[0], bids[1], fallbackRate,
                snapshot.spend() == null ? BigDecimal.ZERO : snapshot.spend(),
                snapshot.p95(),
                ads[0], ads[1], ads[2], ads[3],
                kafkaLag,
                health,
                snapshot.trend());
    }

    /**
     * 最近竞价。按主键倒序，限制 1~50 行，避免被当成分页接口打爆。
     */
    public List<RecentBidView> recentBids(int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        return jdbc.query(
                "SELECT request_id, ad_id, win_bid, pay_price, has_ad, cost_ms, "
                        + "DATE_FORMAT(bid_at, '%Y-%m-%d %H:%i:%s') AS bid_at, "
                        + "trace_json IS NOT NULL AS has_trace "
                        + "FROM bid_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> new RecentBidView(
                        rs.getString("request_id"),
                        rs.getObject("ad_id", Long.class),
                        rs.getBigDecimal("win_bid"),
                        rs.getBigDecimal("pay_price"),
                        rs.getInt("has_ad") == 1,
                        rs.getInt("cost_ms"),
                        rs.getString("bid_at"),
                        rs.getBoolean("has_trace")),
                cap);
    }

    private Snapshot loadSnapshot(LocalDateTime dayStart, long dayStartEpochMillis,
                                  LocalDateTime trendFrom) {
        long[] bids = bidCounts(dayStart);
        long[] ads = adCounts();
        BigDecimal spend = jdbc.queryForObject(
                "SELECT COALESCE(SUM(pay_price), 0) FROM billing_event WHERE ts >= ?",
                BigDecimal.class, dayStartEpochMillis);
        long p95 = p95CostMs(dayStart);
        List<TrendPoint> trend = trend(trendFrom);
        return new Snapshot(bids, ads, spend, p95, trend);
    }

    private List<TrendPoint> trend(LocalDateTime from) {
        return jdbc.query(
                "SELECT DATE_FORMAT(bid_at, '%H:%i') AS minute, COUNT(*) AS bids, "
                        + "COALESCE(SUM(CASE WHEN ad_id = ? THEN 1 ELSE 0 END), 0) AS fallback "
                        + "FROM bid_log WHERE bid_at >= ? "
                        + "GROUP BY DATE_FORMAT(bid_at, '%H:%i') ORDER BY MIN(bid_at)",
                (rs, i) -> new TrendPoint(rs.getString("minute"), rs.getLong("bids"), rs.getLong("fallback")),
                FallbackAdProvider.STATIC_AD_ID, from);
    }

    private long[] bidCounts(LocalDateTime dayStart) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN ad_id = ? THEN 1 ELSE 0 END), 0) AS fallback "
                        + "FROM bid_log WHERE bid_at >= ?",
                (rs, i) -> new long[]{rs.getLong("total"), rs.getLong("fallback")},
                FallbackAdProvider.STATIC_AD_ID, dayStart);
    }

    private long[] adCounts() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS active, "
                        + "COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS paused, "
                        + "COALESCE(SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END), 0) AS exhausted "
                        + "FROM ad",
                (rs, i) -> new long[]{
                        rs.getLong("total"), rs.getLong("active"),
                        rs.getLong("paused"), rs.getLong("exhausted")});
    }

    /** 取今日最近 1000 条耗时的 p95；没有数据返回 0。 */
    private long p95CostMs(LocalDateTime dayStart) {
        List<Integer> costs = jdbc.queryForList(
                "SELECT cost_ms FROM bid_log WHERE bid_at >= ? ORDER BY id DESC LIMIT 1000",
                Integer.class, dayStart);
        if (costs.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(costs);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private Map<String, String> health() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            HealthComponent component = healthEndpoint.health();
            if (component == null) {
                // Actuator 在端点未就绪时可能返回 null；概览降级为 UNKNOWN 而不是抛 NPE。
                out.put("overall", "UNKNOWN");
            } else {
                out.put("overall", component.getStatus().getCode());
                if (component instanceof CompositeHealth composite && composite.getComponents() != null) {
                    composite.getComponents().forEach((name, detail) ->
                            out.put(name, detail.getStatus().getCode()));
                }
            }
        } catch (Exception e) {
            log.warn("概览查询健康状态失败: {}", e.getMessage());
            out.put("overall", "UNKNOWN");
        }
        return out;
    }

    /** 同一个 REPEATABLE_READ 快照里读到的五个结果。 */
    private record Snapshot(long[] bids, long[] ads, BigDecimal spend, long p95,
                            List<TrendPoint> trend) {
    }
}
