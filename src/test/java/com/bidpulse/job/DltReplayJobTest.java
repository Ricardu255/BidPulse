package com.bidpulse.job;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.event.BidEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DLT 重放单测（不连 Kafka）。
 *
 * <p>覆盖：可解析事件带重放计数头重新投递主主题、不可解析报文不重放、
 * 达到上限不重放、投递失败必须抛出（让调用方不提交位点）。
 */
@ExtendWith(MockitoExtension.class)
class DltReplayJobTest {

    private static final String DLT = "bid_event.DLT";
    private static final String MAIN = "bid_event";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DltReplayJob job;

    @BeforeEach
    void setUp() {
        BidPulseProperties props = new BidPulseProperties();
        props.getKafka().setTopic(MAIN);
        props.getKafka().setDltTopic(DLT);
        props.getKafka().setDltReplayMaxAttempts(3);
        props.getKafka().setDltReplaySendTimeoutMs(1000);
        job = new DltReplayJob(new KafkaProperties(), kafkaTemplate, objectMapper,
                props, new SimpleMeterRegistry());
    }

    @Test
    void replayRecord_sendsToMainTopicWithIncrementedAttemptHeader() {
        stubSuccessfulSend();
        ConsumerRecord<String, String> record = dltRecord(validEvent("evt-1"), 1);

        assertEquals(DltReplayJob.ReplayOutcome.REPLAYED, job.replayRecord(record));

        ProducerRecord<String, String> sent = sentRecord();
        assertEquals(MAIN, sent.topic(), "必须投回主主题，而不是继续留在 DLT");
        assertEquals("900010", sent.key(), "保留原分区键，保证同一广告事件顺序");
        assertEquals(validEvent("evt-1"), sent.value(), "报文内容不能改写");
        assertEquals("2", header(sent), "已有重放 1 次，重放后计数应为 2");
    }

    @Test
    void replayRecord_firstReplayWritesAttemptOne() {
        stubSuccessfulSend();
        ConsumerRecord<String, String> record = dltRecord(validEvent("evt-2"), null);

        assertEquals(DltReplayJob.ReplayOutcome.REPLAYED, job.replayRecord(record));

        assertEquals("1", header(sentRecord()), "首次重放写入 1，而不是 0");
    }

    @Test
    void replayRecord_skipsInvalidJson_withoutSending() {
        ConsumerRecord<String, String> record = dltRecord("not-a-json", null);

        assertEquals(DltReplayJob.ReplayOutcome.SKIPPED_INVALID, job.replayRecord(record));
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void replayRecord_skipsEventWithoutEventId_withoutSending() throws Exception {
        // 缺少 eventId 无法做幂等去重，重放多少次都无法被消费端接受
        String json = objectMapper.writeValueAsString(BidEvent.builder().requestId("req-1").build());
        ConsumerRecord<String, String> record = dltRecord(json, null);

        assertEquals(DltReplayJob.ReplayOutcome.SKIPPED_INVALID, job.replayRecord(record));
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void replayRecord_skipsWhenMaxAttemptsReached_withoutSending() {
        ConsumerRecord<String, String> record = dltRecord(validEvent("evt-3"), 3);

        assertEquals(DltReplayJob.ReplayOutcome.SKIPPED_EXHAUSTED, job.replayRecord(record));
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    /** 投递失败必须抛出：调用方据此不提交该条位点，下一轮重新拉取。 */
    @Test
    void replayRecord_throwsWhenSendFails() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        ConsumerRecord<String, String> record = dltRecord(validEvent("evt-4"), null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> job.replayRecord(record));
        assertTrue(ex.getMessage().contains("broker down"),
                "异常应带上真实失败原因，实际=" + ex.getMessage());
    }

    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    private ProducerRecord<String, String> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<String, String> record) {
        Header header = record.headers().lastHeader(DltReplayJob.ATTEMPT_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String validEvent(String eventId) {
        try {
            return objectMapper.writeValueAsString(BidEvent.builder()
                    .eventId(eventId)
                    .requestId("req-" + eventId)
                    .adId(900010L)
                    .advertiserId(900010)
                    .payPrice(new BigDecimal("0.10"))
                    .ts(1234567890L)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> dltRecord(String value, Integer replayCount) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(DLT, 0, 42L, "900010", value);
        if (replayCount != null) {
            record.headers().add(DltReplayJob.ATTEMPT_HEADER,
                    String.valueOf(replayCount).getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
