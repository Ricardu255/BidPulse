package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 消费模板的错误分类单测 —— 不依赖 Kafka / MySQL / Redis。
 *
 * <p>锁住两条「不报错、但会让错误处理器做错事」的语义：
 * <ol>
 *   <li>报文无法解析 → 抛 {@link InvalidBidEventException}（不可重试，直接进 DLT），
 *       而不是普通 RuntimeException（会被退避重试若干次）；</li>
 *   <li>报文可解析但缺少 eventId → 同样不可重试，否则会在 {@code processed_event}
 *       的 NOT NULL 约束上反复失败。</li>
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

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("bid_event", 0, 0L, "ad-key", value);
    }

    /** 假 JdbcTemplate：processed_event 的 INSERT IGNORE 恒返回 1（首次处理） */
    private static final JdbcTemplate INSERT_OK = new JdbcTemplate() {
        @Override
        public int update(String sql, Object... args) {
            return 1;
        }
    };

    private static final class TestConsumer extends AbstractBidEventConsumer {

        private final List<BidEvent> handled = new ArrayList<>();

        private TestConsumer() {
            super(INSERT_OK, new ObjectMapper(), "test-group");
        }

        @Override
        protected void handle(BidEvent event) {
            handled.add(event);
        }

        private void process(ConsumerRecord<String, String> rec, Acknowledgment ack) {
            super.consume(rec, ack);
        }
    }
}
