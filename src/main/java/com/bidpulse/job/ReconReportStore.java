package com.bidpulse.job;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 保存最近一次预算对账结果，供控制台只读查询。
 *
 * <p>只保留内存中的最后一份，不落库：对账是周期任务，控制台只需要看“最近一次有没有跑、
 * 结果如何”。进程重启后为空是正常状态，下一次调度会自动填充。
 */
@Component
public class ReconReportStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private volatile ReconReport last;

    public ReconReport record(String status, int adCount, int alerted, int repaired,
                              long durationMs, String message, List<ReconMismatch> mismatches) {
        ReconReport report = new ReconReport(
                LocalDateTime.now().format(FMT), status, adCount, alerted, repaired,
                durationMs, message, mismatches == null ? List.of() : List.copyOf(mismatches));
        this.last = report;
        return report;
    }

    /** 最近一次结果；从未跑过时为 null。 */
    public ReconReport last() {
        return last;
    }

    /**
     * @param status  OK / FAILED
     * @param adCount 实际对账广告数
     * @param alerted Redis 高于账单的告警数
     * @param repaired 按账单修复的广告数
     * @param message  失败原因（成功时为 null）
     */
    public record ReconReport(
            String lastRunAt,
            String status,
            int adCount,
            int alerted,
            int repaired,
            long durationMs,
            String message,
            List<ReconMismatch> mismatches) {

        /** 兼容旧调用 / 旧测试：没有差异明细时给空列表。 */
        public ReconReport(String lastRunAt, String status, int adCount, int alerted,
                           int repaired, long durationMs, String message) {
            this(lastRunAt, status, adCount, alerted, repaired, durationMs, message, List.of());
        }
    }
}
