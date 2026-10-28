package com.bidpulse.job;

import com.bidpulse.config.BidPulseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * {@code processed_event} 去重表保留期清理。
 *
 * <p><b>为什么需要</b>：每条竞价事件在三个消费组各写一行去重记录，且永不主动删除。
 * 1000 QPS 下每天约 2.6 亿行，几个月就能把表撑到不可维护（索引膨胀、备份变慢）。
 *
 * <p><b>保留期怎么定</b>：必须不短于 Kafka 的 {@code retention.ms}（默认 7 天）。
 * 若消息还在 broker 上、去重记录却已被删，一旦消费组重置并重放，就会重复计费。
 * 因此默认保留 8 天，牺牲一天空间换取安全边界。
 *
 * <p><b>为什么分批循环</b>：一次性删除几千万行会形成长事务、持有大量锁并撑大 undo。
 * 这里按 {@code batch} 行一批循环删除，批间短暂让出；单次调度再设总上限，
 * 避免一个维护窗口被清理任务独占，剩余部分次日继续。
 * 清理失败只记日志，不影响消费正确性（只影响表的体积）。
 */
@Slf4j
@Component
public class ProcessedEventCleaner {

    private final JdbcTemplate jdbc;
    private final BidPulseProperties props;

    public ProcessedEventCleaner(JdbcTemplate jdbc, BidPulseProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 每日 02:30 清理过期去重记录（cron 可配） */
    @Scheduled(cron = "${bidpulse.kafka.processed-event-cleanup-cron:0 30 2 * * ?}")
    public void clean() {
        BidPulseProperties.Kafka kafka = props.getKafka();
        int days = Math.max(1, kafka.getProcessedEventRetentionDays());
        int batch = Math.max(1, kafka.getProcessedEventCleanupBatch());
        int maxPerRun = Math.max(batch, kafka.getProcessedEventCleanupMaxPerRun());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);

        int total = 0;
        try {
            while (total < maxPerRun) {
                int deleted = jdbc.update(
                        "DELETE FROM processed_event WHERE created_at < ? LIMIT ?",
                        Timestamp.valueOf(cutoff), batch);
                total += deleted;
                if (deleted < batch) {
                    break;                  // 已无更多过期行
                }
                Thread.sleep(50);           // 批间让出，避免长时间占满 DB/IO
            }
            log.info("processed_event 清理完成：保留 {} 天（cutoff={}），本次删除 {} 行{}",
                    days, cutoff, total,
                    total >= maxPerRun ? "（达到单次上限，剩余由下次调度继续）" : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("processed_event 清理被中断，本次已删除 {} 行", total);
        } catch (Exception e) {
            // 表结构未升级（缺 created_at）或 DB 暂时不可用：记日志，下次调度重试
            log.warn("processed_event 清理失败（本次已删除 {} 行，下次调度重试）: {}", total, e.getMessage());
        }
    }
}
