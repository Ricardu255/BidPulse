package com.bidpulse.job;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.infra.redis.BudgetService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预算对账：对比 Redis {@code spent:*} 与 MySQL {@code billing_event} 当日汇总。
 *
 * <p><b>为什么需要它</b>：预算是「Redis 先扣、DB 后记账」的异步链路。Redis 扣减成功而
 * Kafka 事件在最终重试后仍投递失败 / 进程在 flush 前崩溃时，Redis 的 spent 会<b>高于</b>
 * DB 账单（多扣了钱却没有计费明细）；反过来，Redis 重启 / FLUSH / key 被误删会让 spent
 * <b>低于</b>账单（当天已消耗被抹掉，额度被重新放出）。两种偏差都不会自愈：
 * 前者是账目缺口，后者是超花风险。
 *
 * <p><b>处理策略</b>：
 * <ul>
 *   <li><b>Redis &gt; DB</b>（超过容差）：说明有事件没落库。钱确实已经从 Redis 扣了，
 *       不能把 spent 调低（否则额度被重复放出）；只记录告警 + 指标，按 {@code event_id}
 *       去重后可在 DLT 里追查，属于「已知账目缺口」。</li>
 *   <li><b>DB &gt; Redis</b>（超过容差）：说明 key 丢失 / 被重置。用
 *       {@link BudgetService#repairSpentFromDb} 按账单重算 Redis（Lua 内重新读取 spent，
 *       只升不降，避免覆盖对账期间并发扣减的结果）。</li>
 *   <li>差值在 {@code tolerance-cents} 内：视为事件在途，正常。</li>
 * </ul>
 *
 * <p><b>为什么不修复 Redis &gt; DB</b>：Redis 的 spent 是「已向广告主收费」的事实来源，
 * 而 billing_event 是异步复制品。以副本为准把已收费金额调小，等于凭空多放预算。
 *
 * <p><b>为什么以 {@code spent} key 为遍历入口</b>：DB 侧可能有全量历史广告，
 * 逐个查账单开销不可控；Redis 只有「当天真正扣过费」的广告，天然把规模收敛到在投集合。
 * 代价是「spent key 存在但 Redis 值丢失」检测不到——那种情况由扣减路径的
 * {@code NEEDS_SEED} 冷启动兜底。
 */
@Slf4j
@Component
public class BudgetReconJob {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** SCAN 建议返回条数（仅 hint，Redis 不保证） */
    private static final int SCAN_COUNT = 500;

    /** 控制台最多展示的差异明细条数，避免一次对账把大列表塞进内存/响应。 */
    private static final int MAX_MISMATCH_DETAILS = 200;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final BudgetService budgetService;
    private final BidPulseProperties props;
    private final MeterRegistry meterRegistry;
    private final ReconReportStore reportStore;

    public BudgetReconJob(StringRedisTemplate redis,
                          JdbcTemplate jdbc,
                          BudgetService budgetService,
                          BidPulseProperties props,
                          MeterRegistry meterRegistry,
                          ReconReportStore reportStore) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.budgetService = budgetService;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.reportStore = reportStore;
    }

    /**
     * 对账结果（供测试断言与日志汇总）。
     *
     * @param adCount  本次实际对账的广告数（Redis 有 spent key 且值可解析）
     * @param alerted  Redis &gt; DB 超过容差、只告警不修复的广告数
     * @param repaired DB &gt; Redis 超过容差、已按账单修复 Redis 的广告数
     */
    public record ReconcileSummary(int adCount, int alerted, int repaired,
                                   List<ReconMismatch> mismatches) {
        /** 兼容旧调用：没有差异明细时给空列表。 */
        public ReconcileSummary(int adCount, int alerted, int repaired) {
            this(adCount, alerted, repaired, List.of());
        }
    }

    /** 默认每小时第 15 分钟对账一次；{@code enabled=false} 时直接跳过。 */
    @Scheduled(cron = "${bidpulse.budget.recon.cron:0 15 * * * ?}")
    public void scheduledReconcile() {
        if (!props.getBudget().getRecon().isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            // 对账失败不应影响业务；下一次调度会重新扫描。Redis/DB 故障时只记录不重试。
            log.warn("预算对账失败（下次调度重试）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 立即执行一轮对账并记录结果，供控制台手动触发。
     *
     * <p>失败时会先把 FAILED 结果写入 {@link ReconReportStore}，再以 RuntimeException 抛出，
     * 让调用方能把 503 返回给页面。
     */
    public synchronized ReconReportStore.ReconReport runOnce() {
        long start = System.currentTimeMillis();
        try {
            ReconcileSummary s = reconcile(LocalDate.now());
            long cost = System.currentTimeMillis() - start;
            ReconReportStore.ReconReport report = reportStore.record(
                    "OK", s.adCount(), s.alerted(), s.repaired(), cost, null, s.mismatches());
            log.info("预算对账完成：对账 {} 条，Redis 高于账单告警 {} 条，按账单修复 {} 条，耗时 {}ms",
                    s.adCount(), s.alerted(), s.repaired(), cost);
            return report;
        } catch (RuntimeException e) {
            reportStore.record("FAILED", 0, 0, 0, System.currentTimeMillis() - start, e.getMessage(), List.of());
            throw e;
        }
    }

    /**
     * 对指定日期执行一轮对账（包级可见，供集成测试固定日期调用）。
     *
     * @return 本轮对账统计
     */
    ReconcileSummary reconcile(LocalDate date) {
        Map<Long, Long> redisSpent = readRedisSpent(date);
        if (redisSpent.isEmpty()) {
            return new ReconcileSummary(0, 0, 0, List.of());
        }

        int batchSize = Math.max(1, props.getBudget().getRecon().getBatchSize());
        long tolerance = Math.max(0, props.getBudget().getRecon().getToleranceCents());

        int checked = 0;
        int alerted = 0;
        int repaired = 0;
        List<ReconMismatch> mismatches = new ArrayList<>();

        List<Long> adIds = new ArrayList<>(redisSpent.keySet());
        for (int from = 0; from < adIds.size(); from += batchSize) {
            List<Long> batch = adIds.subList(from, Math.min(adIds.size(), from + batchSize));
            Map<Long, Long> dbSpent = loadDbSpent(batch, date);
            Map<Long, BigDecimal> budgets = loadBudgets(batch);

            for (Long adId : batch) {
                long redisCents = redisSpent.get(adId);
                long dbCents = dbSpent.getOrDefault(adId, 0L);
                checked++;

                long diff = redisCents - dbCents;   // > 0：Redis 多扣（事件未落库）
                if (diff > tolerance) {
                    alerted++;
                    meterRegistry.counter("bidpulse.budget.recon.mismatch", "direction", "redis_ahead")
                            .increment();
                    if (mismatches.size() < MAX_MISMATCH_DETAILS) {
                        mismatches.add(new ReconMismatch(adId, redisCents, dbCents, diff,
                                "REDIS_AHEAD", "ALERT"));
                    }
                    log.warn("预算对账不一致：Redis 高于账单 adId={} date={} redis={}分 db={}分 diff={}分"
                                    + "（疑似计费事件未落库，检查 DLT / 消费者滞后；不自动下调 spent）",
                            adId, date, redisCents, dbCents, diff);
                    continue;
                }
                if (-diff <= tolerance) {
                    continue;   // 容差内：事件在途，正常
                }

                // DB > Redis：key 丢失 / 被重置，需要按账单重建
                BigDecimal dailyBudget = budgets.get(adId);
                if (dailyBudget == null) {
                    // 广告已被删除 / 不在 ad 表：无法得知日预算，无法重算余额；
                    // 保留 Redis 现状并告警，由人工确认是否清理残留 key。
                    if (mismatches.size() < MAX_MISMATCH_DETAILS) {
                        mismatches.add(new ReconMismatch(adId, redisCents, dbCents, diff,
                                "DB_AHEAD", "SKIPPED"));
                    }
                    log.warn("预算对账无法修复：ad 表缺少该广告 adId={} date={} db={}分 redis={}分",
                            adId, date, dbCents, redisCents);
                    continue;
                }
                boolean ok = budgetService.repairSpentFromDb(adId, dailyBudget, dbCents, date);
                if (ok) {
                    repaired++;
                    if (mismatches.size() < MAX_MISMATCH_DETAILS) {
                        mismatches.add(new ReconMismatch(adId, redisCents, dbCents, diff,
                                "DB_AHEAD", "REPAIRED"));
                    }
                    meterRegistry.counter("bidpulse.budget.recon.repaired").increment();
                    log.warn("预算对账修复：Redis 低于账单，已按账单重算 adId={} date={} db={}分 redis={}分",
                            adId, date, dbCents, redisCents);
                }
                // ok=false：并发扣减已把 spent 推到账单之上（Lua 内只升不降），无需修复
            }
        }
        return new ReconcileSummary(checked, alerted, repaired, mismatches);
    }

    /**
     * SCAN 出当日所有 {@code spent:{adId}:{date}} key 并批量读取。
     *
     * <p>SCAN 而不是 KEYS：后者 O(N) 且阻塞 Redis 单线程。SCAN 可能返回重复 key，
     * 用 {@link LinkedHashSet} 去重并保持稳定顺序。
     */
    private Map<Long, Long> readRedisSpent(LocalDate date) {
        String suffix = ":" + date.format(DATE_FMT);
        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match("spent:*" + suffix)
                .count(SCAN_COUNT)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // 形如 spent:900010:20261029；前缀/后缀已由 match 保证，这里再校验分段数
                if (key != null && key.startsWith("spent:") && key.endsWith(suffix)) {
                    keys.add(key);
                }
            }
        }

        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Long> result = new HashMap<>(keys.size());
        List<String> keyList = new ArrayList<>(keys);
        int batchSize = Math.max(1, props.getBudget().getRecon().getBatchSize());
        for (int from = 0; from < keyList.size(); from += batchSize) {
            List<String> batch = keyList.subList(from, Math.min(keyList.size(), from + batchSize));
            List<String> values = redis.opsForValue().multiGet(batch);
            for (int i = 0; i < batch.size(); i++) {
                Long adId = parseAdId(batch.get(i));
                if (adId == null) {
                    continue;
                }
                String v = (values == null || i >= values.size()) ? null : values.get(i);
                if (v == null) {
                    continue;   // 扫描与读取之间被清理，跳过
                }
                try {
                    long cents = new BigDecimal(v).setScale(0, RoundingMode.DOWN).longValueExact();
                    result.put(adId, Math.max(0, cents));
                } catch (ArithmeticException | NumberFormatException e) {
                    // 脏值不能当 0：0 会触发「DB > Redis」的修复分支，把脏值掩盖掉。
                    log.warn("预算对账跳过脏 spent 值 adId={} date={} value={}", adId, date, v);
                }
            }
        }
        return result;
    }

    /** {@code spent:{adId}:{date}} → adId；格式不符返回 null。 */
    private static Long parseAdId(String key) {
        String[] parts = key.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 批量查询一批广告在指定日期的 billing_event 汇总（分）。 */
    private Map<Long, Long> loadDbSpent(List<Long> adIds, LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

        String placeholders = String.join(",", Collections.nCopies(adIds.size(), "?"));
        List<Object> args = new ArrayList<>(adIds.size() + 2);
        args.addAll(adIds);
        args.add(start);
        args.add(end);

        Map<Long, Long> out = new HashMap<>(adIds.size() * 2);
        jdbc.query(
                "SELECT ad_id, COALESCE(SUM(pay_price), 0) AS spent FROM billing_event "
                        + "WHERE ad_id IN (" + placeholders + ") AND ts >= ? AND ts < ? "
                        + "GROUP BY ad_id",
                rs -> {
                    out.put(rs.getLong("ad_id"), toCents(rs.getBigDecimal("spent")));
                },
                args.toArray());
        return out;
    }

    /** 批量查询一批广告的日预算；已删除的广告不会出现在结果里。 */
    private Map<Long, BigDecimal> loadBudgets(List<Long> adIds) {
        String placeholders = String.join(",", Collections.nCopies(adIds.size(), "?"));
        Map<Long, BigDecimal> out = new HashMap<>(adIds.size() * 2);
        jdbc.query(
                "SELECT id, daily_budget FROM ad WHERE id IN (" + placeholders + ")",
                rs -> {
                    out.put(rs.getLong("id"), rs.getBigDecimal("daily_budget"));
                },
                adIds.toArray());
        return out;
    }

    /**
     * 元 → 分，向下取整。
     *
     * <p>必须与 {@link BudgetService} 的计费口径一致（{@code pay_price} 写入前已量化到分），
     * 否则 4 位小数的账单汇总会与 Redis 的分值产生恒定的亚分误差，天天误报。
     */
    private static long toCents(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
