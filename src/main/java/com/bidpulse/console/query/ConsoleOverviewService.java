package com.bidpulse.console.query;

import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.console.dto.RecentBidView;
import com.bidpulse.console.dto.TrendPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
 * <p>这些查询只服务控制台，不在竞价热路径上；但都限制时间范围 / 行数，避免全表扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleOverviewService {

    /** 与 fallback 广告的固定 id 对齐；用 ad_id 识别兜底，避免给 bid_log 加列。 */
    private static final long FALLBACK_AD_ID = 999999L;

    private final JdbcTemplate jdbc;
    private final KafkaLagQueryService kafkaLagQueryService;
    private final HealthEndpoint healthEndpoint;

    public OverviewView overview() {
        long[] bids = bidCounts();
        long[] ads = adCounts();
        BigDecimal spend = jdbc.queryForObject(
                "SELECT COALESCE(SUM(pay_price), 0) FROM billing_event "
                        + "WHERE ts >= UNIX_TIMESTAMP(CURDATE()) * 1000",
                BigDecimal.class);
        long p95 = p95CostMs();

        List<TrendPoint> trend = jdbc.query(
                "SELECT DATE_FORMAT(bid_at, '%H:%i') AS minute, COUNT(*) AS bids, "
                        + "COALESCE(SUM(CASE WHEN ad_id = ? THEN 1 ELSE 0 END), 0) AS fallback "
                        + "FROM bid_log WHERE bid_at >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) "
                        + "GROUP BY DATE_FORMAT(bid_at, '%H:%i') ORDER BY MIN(bid_at)",
                (rs, i) -> new TrendPoint(rs.getString("minute"), rs.getLong("bids"), rs.getLong("fallback")),
                FALLBACK_AD_ID);

        double fallbackRate = bids[0] == 0 ? 0.0 : (double) bids[1] / bids[0];
        long kafkaLag;
        try {
            kafkaLag = kafkaLagQueryService.totalLag();
        } catch (Exception e) {
            log.warn("概览查询 Kafka lag 失败: {}", e.getMessage());
            kafkaLag = -1;
        }

        return new OverviewView(
                bids[0], bids[1], fallbackRate,
                spend == null ? BigDecimal.ZERO : spend,
                p95,
                ads[0], ads[1], ads[2], ads[3],
                kafkaLag,
                health(),
                trend);
    }

    /**
     * 最近竞价。按主键倒序，限制 1~50 行，避免被当成分页接口打爆。
     */
    public List<RecentBidView> recentBids(int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        return jdbc.query(
                "SELECT request_id, ad_id, win_bid, pay_price, has_ad, cost_ms, "
                        + "DATE_FORMAT(bid_at, '%Y-%m-%d %H:%i:%s') AS bid_at "
                        + "FROM bid_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> new RecentBidView(
                        rs.getString("request_id"),
                        rs.getObject("ad_id", Long.class),
                        rs.getBigDecimal("win_bid"),
                        rs.getBigDecimal("pay_price"),
                        rs.getInt("has_ad") == 1,
                        rs.getInt("cost_ms"),
                        rs.getString("bid_at")),
                cap);
    }

    private long[] bidCounts() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN ad_id = ? THEN 1 ELSE 0 END), 0) AS fallback "
                        + "FROM bid_log WHERE bid_at >= CURDATE()",
                (rs, i) -> new long[]{rs.getLong("total"), rs.getLong("fallback")},
                FALLBACK_AD_ID);
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
    private long p95CostMs() {
        List<Integer> costs = jdbc.queryForList(
                "SELECT cost_ms FROM bid_log WHERE bid_at >= CURDATE() ORDER BY id DESC LIMIT 1000",
                Integer.class);
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
            out.put("overall", component.getStatus().getCode());
            if (component instanceof CompositeHealth composite && composite.getComponents() != null) {
                composite.getComponents().forEach((name, detail) ->
                        out.put(name, detail.getStatus().getCode()));
            }
        } catch (Exception e) {
            log.warn("概览查询健康状态失败: {}", e.getMessage());
            out.put("overall", "UNKNOWN");
        }
        return out;
    }
}
