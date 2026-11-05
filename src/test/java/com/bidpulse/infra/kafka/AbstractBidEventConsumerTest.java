package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 消费模板的错误分类与幂等语义单测 —— 不依赖 Kafka / MySQL / Redis。
 *
 * <p>锁住三类「不报错、但会让错误处理器做错事」的语义：
 * <ol>
 *   <li>报文无法解析 / 缺少 eventId → {@link InvalidBidEventException}（不可重试，直接进 DLT）；</li>
 *   <li>同一 eventId 同一载荷 → 正常重复投递，跳过且 ack；</li>
 *   <li>同一 eventId 不同载荷 → {@link BidEventPayloadConflictException}，转 DLT，
 *       绝不能静默跳过或覆盖已有账单。</li>
 * </ol>
 */
class AbstractBidEventConsumerTest {

    @Test
    @DisplayName("非法 JSON → InvalidBidEventException，且不执行业务写")
    void malformedJson_isInvalidAndSkipsHandle() {
        TestConsumer consumer = new TestConsumer();
        assertThrows(InvalidBidEventException.class,
                () -> consumer.process(record("{not-json"), mock(Acknowledgment.class)));
        assertEquals(0, consumer.handled.size(), "解析失败不应进入业务处理");
    }

    @Test
    @DisplayName("缺少 eventId → InvalidBidEventException（无法幂等去重，不可重试）")
    void missingEventId_isInvalid() {
        TestConsumer consumer = new TestConsumer();
        assertThrows(InvalidBidEventException.class,
                () -> consumer.process(record("{\"requestId\":\"r-1\"}"), mock(Acknowledgment.class)));
        assertEquals(0, consumer.handled.size());
    }

    @Test
    @DisplayName("合法事件：去重通过 → 业务处理 → ack")
    void validEvent_isHandledAndAcked() {
        Acknowledgment ack = mock(Acknowledgment.class);
        TestConsumer consumer = new TestConsumer();

        consumer.process(record("{\"eventId\":\"evt-1\",\"adId\":123}"), ack);

        assertEquals(1, consumer.handled.size());
        assertEquals("evt-1", consumer.handled.get(0).getEventId());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("同一 eventId 同一载荷重复投递：只处理一次，第二次直接 ack")
    void duplicateWithSamePayload_isSkipped() {
        Acknowledgment ack = mock(Acknowledgment.class);
        TestConsumer consumer = new TestConsumer();
        String json = "{\"eventId\":\"evt-2\",\"adId\":123,\"payPrice\":0.0200}";

        consumer.process(record(json), ack);
        // 数值写法不同但语义相同（0.0200 vs 0.02），指纹必须一致
        consumer.process(record(json.replace("0.0200", "0.02")), ack);

        assertEquals(1, consumer.handled.size(), "重复投递不能重复执行业务");
        verify(ack, times(2)).acknowledge();
    }

    @Test
    @DisplayName("同一 eventId 不同载荷：抛冲突异常，不执行业务，也不 ack")
    void duplicateWithDifferentPayload_throwsConflict() {
        Acknowledgment ack = mock(Acknowledgment.class);
        TestConsumer consumer = new TestConsumer();

        consumer.process(record("{\"eventId\":\"evt-3\",\"adId\":123,\"payPrice\":1.00}"), ack);

        assertThrows(BidEventPayloadConflictException.class,
                () -> consumer.process(
                        record("{\"eventId\":\"evt-3\",\"adId\":123,\"payPrice\":2.00}"), ack));

        assertEquals(1, consumer.handled.size(), "冲突事件不能再执行业务写");
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("旧版记录 payload_hash 为空：回填指纹并按重复跳过，不误报冲突")
    void legacyNullHash_isBackfilledAndSkipped() {
        Acknowledgment ack = mock(Acknowledgment.class);
        FakeProcessedEventJdbc jdbc = new FakeProcessedEventJdbc();
        jdbc.seedLegacy("evt-legacy", "test-group");
        TestConsumer consumer = new TestConsumer(jdbc);

        consumer.process(record("{\"eventId\":\"evt-legacy\",\"adId\":123}"), ack);

        assertEquals(0, consumer.handled.size());
        assertTrue(jdbc.hashOf("evt-legacy", "test-group") != null, "旧记录应被回填指纹");
        verify(ack).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("bid_event", 0, 0L, "ad-key", value);
    }

    /** 内存版 processed_event：模拟 INSERT IGNORE、指纹查询与旧记录回填。 */
    private static final class FakeProcessedEventJdbc extends JdbcTemplate {

        private final Map<String, String> hashes = new HashMap<>();

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT IGNORE INTO processed_event")) {
                String key = key(args[0], args[1]);
                if (hashes.containsKey(key)) {
                    return 0;
                }
                hashes.put(key, (String) args[2]);
                return 1;
            }
            if (sql.startsWith("UPDATE processed_event SET payload_hash")) {
                String key = key(args[1], args[2]);
                if (hashes.containsKey(key) && hashes.get(key) == null) {
                    hashes.put(key, (String) args[0]);
                    return 1;
                }
                return 0;
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.startsWith("SELECT payload_hash FROM processed_event")) {
                return requiredType.cast(hashes.get(key(args[0], args[1])));
            }
            throw new UnsupportedOperationException("未预期的 SQL: " + sql);
        }

        void seedLegacy(String eventId, String group) {
            hashes.put(key(eventId, group), null);
        }

        String hashOf(String eventId, String group) {
            return hashes.get(key(eventId, group));
        }

        private static String key(Object eventId, Object group) {
            return eventId + "|" + group;
        }
    }

    private static final class TestConsumer extends AbstractBidEventConsumer {

        private final List<BidEvent> handled = new ArrayList<>();

        private TestConsumer() {
            this(new FakeProcessedEventJdbc());
        }

        private TestConsumer(JdbcTemplate jdbc) {
            super(jdbc, new ObjectMapper(), "test-group");
        }

        @Override
        protected void handle(BidEvent event, String payloadHash) {
            handled.add(event);
        }

        private void process(ConsumerRecord<String, String> rec, Acknowledgment ack) {
            super.consume(rec, ack);
        }
    }
}
