package com.bidpulse.job;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.event.BidEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DLT（{@code bid_event.DLT}）重放任务。
 *
 * <p><b>为什么需要它</b>：{@code KafkaErrorHandlingConfig} 已经用 1s→16s 的指数退避把
 * 瞬时故障吸收掉，剩下的 DLT 消息多为<b>持续时间超过约 31s 的故障</b>（DB 主从切换、
 * 连接池耗尽、下游长时间不可用）。这类事件是可恢复的，若留在 DLT 里等人工处理，
 * 计费账单会一直缺口，预算对账只能告警不能自愈。重放任务把它们延迟重新投回主主题，
 * 由三个消费组按 {@code processed_event(event_id, grp)} 幂等消费——重复投递是安全的。
 *
 * <p><b>不可重放</b>：报文本身无法解析 / 缺少 {@code eventId} 的消息重放多少次都不会成功，
 * 直接跳过并推进位点，留在 DLT 供人工排查（不删除消息）。
 *
 * <p><b>重放上限</b>：{@value #ATTEMPT_HEADER} 记录已重放次数。达到
 * {@code bidpulse.kafka.dlt-replay-max-attempts} 后不再重放，避免「一次持续故障
 * → 无限重放 → DLT 永远不收敛」。头部只在新消息里重建，不携带 {@code kafka_dlt-*} 等
 * 框架头，防止多次往返后头部不断膨胀。
 *
 * <p><b>位点语义</b>：使用独立消费组 {@value #REPLAY_GROUP}、手动提交。
 * 每成功处理一条就把它计入待提交位点；若某条投递失败（{@code send} 超时 / broker 不可达），
 * 已提交的截止到上一条，本条及之后留到下一轮——宁可重复重放（幂等），也不丢事件。
 *
 * <p><b>为什么用原生 KafkaConsumer</b>：重放需要「拉一批 → 有限时长内处理 → 精确提交」，
 * 与 {@code @KafkaListener} 的常驻容器模型不同；每次调度创建一个短生命周期 consumer，
 * 处理上限后立即关闭，不占长期连接。
 */
@Slf4j
@Component
public class DltReplayJob {

    /** 重放次数头：值为十进制字符串，0 表示尚未重放过 */
    static final String ATTEMPT_HEADER = "x-replay-count";

    /** 重放专用消费组，与三个业务消费组隔离 */
    public static final String REPLAY_GROUP = "dlt-replay";

    /** 单次 poll 的最长阻塞时间，保证调度能在 max-duration 内及时收敛 */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    /** 单次 poll 的最大记录数（同时受 max-records 总上限约束） */
    private static final int POLL_MAX_RECORDS = 500;

    /** 单条重放结果 */
    public enum ReplayOutcome {
        /** 已重新投递到主主题 */
        REPLAYED,
        /** 报文不可解析 / 缺少 eventId，不重放（保留在 DLT） */
        SKIPPED_INVALID,
        /** 已达到最大重放次数，不重放（保留在 DLT） */
        SKIPPED_EXHAUSTED
    }

    /** 一轮重放的统计（供日志、控制台与测试断言） */
    public record ReplayStats(int replayed, int skippedInvalid, int skippedExhausted) {
    }

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final BidPulseProperties props;
    private final MeterRegistry meterRegistry;

    public DltReplayJob(KafkaProperties kafkaProperties,
                        KafkaTemplate<String, String> kafkaTemplate,
                        ObjectMapper objectMapper,
                        BidPulseProperties props,
                        MeterRegistry meterRegistry) {
        this.kafkaProperties = kafkaProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /** 默认每 5 分钟重放一轮；{@code enabled=false} 时直接跳过。 */
    @Scheduled(cron = "${bidpulse.kafka.dlt-replay-cron:0 */5 * * * ?}")
    public void scheduledReplay() {
        if (!props.getKafka().isDltReplayEnabled()) {
            return;
        }
        try {
            ReplayStats s = replayOnce();
            if (s.replayed() > 0 || s.skippedInvalid() > 0 || s.skippedExhausted() > 0) {
                log.info("DLT 重放完成：重放 {} 条，跳过不可解析 {} 条，跳过超限 {} 条",
                        s.replayed(), s.skippedInvalid(), s.skippedExhausted());
            }
        } catch (Exception e) {
            // 调度失败不影响业务；未提交的位点会在下一轮重新拉取
            log.warn("DLT 重放失败（下轮重试）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 执行一轮重放（使用配置里的 max-records；包级可见，供测试直接调用）。
     */
    ReplayStats replayOnce() {
        return replayOnce(Math.max(1, props.getKafka().getDltReplayMaxRecords()));
    }

    /**
     * 执行一轮重放，允许控制台临时指定单轮上限。
     *
     * <p>{@code synchronized}：定时任务与控制台手动触发共用同一个消费组位点，
     * 并发跑两轮会让位点提交交叉，虽然幂等但会产生无意义的重复重放。
     *
     * @param maxRecords 单轮最多处理的 DLT 记录数
     */
    public synchronized ReplayStats replayOnce(int maxRecords) {
        BidPulseProperties.Kafka cfg = props.getKafka();
        maxRecords = Math.max(1, maxRecords);
        long maxDurationMs = Math.max(1, cfg.getDltReplayMaxDurationMs());

        // 本项目未配置 SSL bundle，传 null 走无 SSL 分支
        Map<String, Object> conf = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        // 独立消费组 + 手动提交：与业务消费组的位点互不影响，且失败时不推进
        conf.put(ConsumerConfig.GROUP_ID_CONFIG, REPLAY_GROUP);
        conf.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        conf.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        conf.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, POLL_MAX_RECORDS);

        int replayed = 0;
        int skippedInvalid = 0;
        int skippedExhausted = 0;
        int processed = 0;
        long deadline = System.currentTimeMillis() + maxDurationMs;
        boolean abort = false;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(conf)) {
            consumer.subscribe(List.of(cfg.getDltTopic()));
            while (!abort && processed < maxRecords && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    if (consumer.assignment().isEmpty()) {
                        // 首次加入消费组时 rebalance 可能还没完成，poll 会先返回空；
                        // 继续 poll 直到分配到分区或达到本轮时长上限，避免白等一个调度周期。
                        continue;
                    }
                    // 已分配分区且本轮无积压；不必空转到 max-duration
                    break;
                }

                Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
                for (ConsumerRecord<String, String> record : records) {
                    if (processed >= maxRecords || System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    ReplayOutcome outcome;
                    try {
                        outcome = replayRecord(record);
                    } catch (Exception e) {
                        // 投递失败：不提交本条位点，已处理的更早记录照常提交，避免重复重放太多
                        log.warn("DLT 重放投递失败（位点停留在本条，下轮重试）partition={} offset={}: {} - {}",
                                record.partition(), record.offset(),
                                e.getClass().getSimpleName(), e.getMessage());
                        abort = true;
                        break;
                    }
                    switch (outcome) {
                        case REPLAYED -> replayed++;
                        case SKIPPED_INVALID -> skippedInvalid++;
                        case SKIPPED_EXHAUSTED -> skippedExhausted++;
                    }
                    meterRegistry.counter("bidpulse.kafka.dlt.replay",
                                    "outcome", outcome.name().toLowerCase())
                            .increment();
                    processed++;
                    toCommit.put(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1));
                }
                if (!toCommit.isEmpty()) {
                    consumer.commitSync(toCommit);
                }
            }
        }
        return new ReplayStats(replayed, skippedInvalid, skippedExhausted);
    }

    /**
     * 处理单条 DLT 记录：校验 → 判断重放次数 → 同步投递主主题。
     *
     * <p>同步等待 {@code send().get(timeout)} 而不是 fire-and-forget：只有 broker 确认写入，
     * 才能提交 DLT 位点；否则「位点已推进、消息没发出去」就是确定性丢失。
     *
     * @throws RuntimeException 投递失败（由调用方决定不提交当前位点）
     */
    public ReplayOutcome replayRecord(ConsumerRecord<String, String> record) {
        BidPulseProperties.Kafka cfg = props.getKafka();

        if (record.value() == null || !parseable(record.value())) {
            log.warn("DLT 记录不可解析，跳过重放 partition={} offset={} value={}",
                    record.partition(), record.offset(), record.value());
            return ReplayOutcome.SKIPPED_INVALID;
        }

        int attempt = readAttempt(record);
        int maxAttempts = Math.max(0, cfg.getDltReplayMaxAttempts());
        if (attempt >= maxAttempts) {
            log.warn("DLT 记录已达最大重放次数，保留待人工处理 attempt={} max={} partition={} offset={}",
                    attempt, maxAttempts, record.partition(), record.offset());
            return ReplayOutcome.SKIPPED_EXHAUSTED;
        }

        ProducerRecord<String, String> out =
                new ProducerRecord<>(cfg.getTopic(), record.key(), record.value());
        // 只带重放计数头：不复制 kafka_dlt-* 等框架头，避免多次往返后头部膨胀
        Header header = new RecordHeader(ATTEMPT_HEADER,
                String.valueOf(attempt + 1).getBytes(StandardCharsets.UTF_8));
        out.headers().add(header);

        try {
            kafkaTemplate.send(out).get(Math.max(1, cfg.getDltReplaySendTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 保持 InterruptedException 的中断标记
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("DLT 重放投递失败: " + e.getMessage(), e);
        }
        log.info("DLT 记录已重放 partition={} offset={} attempt={} key={}",
                record.partition(), record.offset(), attempt + 1, record.key());
        return ReplayOutcome.REPLAYED;
    }

    /** 校验报文可解析且带 eventId；与消费端 {@code InvalidBidEventException} 口径一致。 */
    private boolean parseable(String value) {
        try {
            BidEvent event = objectMapper.readValue(value, BidEvent.class);
            return event.getEventId() != null && !event.getEventId().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取重放次数头；缺失或非法值按 0 处理（首次进入 DLT）。 */
    private static int readAttempt(ConsumerRecord<String, String> record) {
        Header header = record.headers() == null ? null : record.headers().lastHeader(ATTEMPT_HEADER);
        if (header == null || header.value() == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
