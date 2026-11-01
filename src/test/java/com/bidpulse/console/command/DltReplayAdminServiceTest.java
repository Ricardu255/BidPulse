package com.bidpulse.console.command;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.job.DltReplayJob;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 单条 DLT 重放的真实 Kafka 集成测试。
 *
 * <p>验证：按 partition+offset 精确取到 DLT 记录、投递回主主题、非法报文跳过、
 * 越界 offset 返回 404、负参数返回 400。
 */
@SpringBootTest
@Tag("integration")
class DltReplayAdminServiceTest {

    @Autowired
    private DltReplayAdminService service;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private KafkaProperties kafkaProperties;
    @Autowired
    private BidPulseProperties props;

    @Test
    @DisplayName("按 partition+offset 重放一条合法 DLT 消息到主主题")
    void replayOne_publishesToMainTopic() throws Exception {
        String topic = props.getKafka().getTopic();
        String dltTopic = props.getKafka().getDltTopic();
        String key = "it-dlt-" + UUID.randomUUID();
        String value = "{\"eventId\":\"" + key + "\",\"requestId\":\"req-" + key
                + "\",\"ts\":1793380000000,\"region\":\"110000\",\"adId\":110,"
                + "\"advertiserId\":11,\"winBid\":1.50,\"payPrice\":1.01,"
                + "\"fallback\":false,\"hasAd\":true,\"priceMode\":1}";

        Map<String, Object> conf = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        conf.put(ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-check-" + UUID.randomUUID());
        conf.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        conf.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(conf)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            assertNotNull(infos, "主主题必须已存在");
            List<TopicPartition> tps = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(tps);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(tps);

            RecordMetadata dltMeta = kafkaTemplate.send(dltTopic, key, value)
                    .get(10, TimeUnit.SECONDS).getRecordMetadata();
            assertEquals(DltReplayJob.ReplayOutcome.REPLAYED,
                    service.replayOne(dltMeta.partition(), dltMeta.offset()));

            for (TopicPartition tp : tps) {
                consumer.seek(tp, endOffsets.getOrDefault(tp, 0L));
            }
            ConsumerRecord<String, String> found = awaitKey(consumer, key);
            assertNotNull(found, "重放消息未在 10s 内出现在主主题");
            assertEquals(value, found.value());
        }
    }

    @Test
    @DisplayName("不可解析报文返回 SKIPPED_INVALID")
    void replayOne_skipsInvalidPayload() throws Exception {
        String key = "it-dlt-invalid-" + UUID.randomUUID();
        RecordMetadata meta = kafkaTemplate.send(props.getKafka().getDltTopic(), key, "not-json")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();

        assertEquals(DltReplayJob.ReplayOutcome.SKIPPED_INVALID,
                service.replayOne(meta.partition(), meta.offset()));
    }

    @Test
    @DisplayName("越界 offset 返回 404，负参数返回 400")
    void replayOne_rejectsInvalidOffsets() {
        BizException notFound = assertThrows(BizException.class,
                () -> service.replayOne(0, Long.MAX_VALUE));
        assertEquals(404, notFound.getStatus());

        BizException badRequest = assertThrows(BizException.class,
                () -> service.replayOne(-1, 0));
        assertEquals(400, badRequest.getStatus());
    }

    private static ConsumerRecord<String, String> awaitKey(KafkaConsumer<String, String> consumer,
                                                           String key) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }
}
