package com.bidpulse.console.query;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.DltMessageView;
import com.bidpulse.job.DltReplayJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DLT 消息只读浏览。
 *
 * <p>使用独立的 {@code assign + seekToBeginning} 消费者，不加入业务消费组、不提交位点，
 * 因此浏览动作不会改变 DLT 重放任务的位点。单次最多返回 50 条、最多等待 2.5 秒，
 * 避免把控制台请求拖住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DltQueryService {

    private static final String REPLAY_HEADER = "x-replay-count";
    private static final int MAX_LIMIT = 50;
    private static final int VALUE_PREVIEW_CHARS = 2000;
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(300);
    private static final long BROWSE_BUDGET_MS = 2500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KafkaProperties kafkaProperties;
    private final BidPulseProperties props;
    private final AdminClient admin;

    public List<DltMessageView> messages(int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        String topic = props.getKafka().getDltTopic();

        Map<String, Object> conf = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        // assign 模式下不会用 group 提交位点，但 Kafka 仍要求一个 group.id
        conf.put(ConsumerConfig.GROUP_ID_CONFIG, "console-dlt-browser");
        conf.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        conf.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        conf.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.min(200, cap * 4));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(conf)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> committedOffsets = committedOffsets();

            // 从每个分区尾部往前取最近 cap 条：既能看到新积压，也能看到被批量任务跳过的旧消息。
            for (TopicPartition tp : partitions) {
                long begin = beginningOffsets.getOrDefault(tp, 0L);
                long end = endOffsets.getOrDefault(tp, begin);
                long start = Math.max(begin, end - Math.max(1, cap));
                consumer.seek(tp, start);
            }

            List<DltMessageView> out = new ArrayList<>(cap);
            long deadline = System.currentTimeMillis() + BROWSE_BUDGET_MS;
            while (out.size() < cap && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    long end = endOffsets.getOrDefault(tp, record.offset() + 1);
                    if (record.offset() >= end) {
                        continue;
                    }
                    long committed = committedOffsets.getOrDefault(tp, 0L);
                    out.add(toView(record, record.offset() < committed));
                    if (out.size() >= cap) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("浏览 DLT 消息失败: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            throw new IllegalStateException("浏览 DLT 消息失败: " + e.getMessage(), e);
        }
    }

    /** 读取 dlt-replay 消费组的已提交位点；查询失败时按 0 处理，至少不隐藏消息。 */
    private Map<TopicPartition, Long> committedOffsets() {
        Map<TopicPartition, Long> out = new HashMap<>();
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                    .listConsumerGroupOffsets(DltReplayJob.REPLAY_GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            committed.forEach((tp, offset) -> out.put(tp, offset.offset()));
        } catch (Exception e) {
            log.debug("读取 dlt-replay 已提交位点失败（按 0 处理）: {}", e.getMessage());
        }
        return out;
    }

    private DltMessageView toView(ConsumerRecord<String, String> record, boolean processed) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                String value = header.value() == null
                        ? ""
                        : new String(header.value(), StandardCharsets.UTF_8);
                headers.put(header.key(), value.length() > 200 ? value.substring(0, 200) + "…" : value);
            }
        }
        int replayCount = parseAttempt(headers.get(REPLAY_HEADER));
        String value = record.value() == null ? null : record.value();
        if (value != null && value.length() > VALUE_PREVIEW_CHARS) {
            value = value.substring(0, VALUE_PREVIEW_CHARS) + "…";
        }
        String timestamp = record.timestamp() > 0
                ? formatTime(record.timestamp())
                : null;
        return new DltMessageView(
                record.topic(), record.partition(), record.offset(),
                record.key(), value, replayCount, processed, timestamp, headers);
    }

    private static String formatTime(long epochMillis) {
        return TIME_FMT.format(Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault()));
    }

    private static int parseAttempt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
