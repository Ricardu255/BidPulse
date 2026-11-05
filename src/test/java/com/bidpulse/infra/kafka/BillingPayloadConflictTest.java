package com.bidpulse.infra.kafka;

import com.bidpulse.event.BidEvent;
import com.bidpulse.testsupport.BidPulseIntegrationTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 计费幂等与载荷冲突的真库集成测试。
 *
 * <p>单测覆盖了消费模板的分支，但「并发重复投递时唯一键怎么表现」「旧账单没有指纹时能否回填」
 * 「processed_event 过保留期后迟到重放还能不能改写金额」只有真 MySQL 才能验证。
 * 这三件事直接对应资损风险，不能只靠 mock。
 */
@SpringBootTest
@BidPulseIntegrationTest
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class BillingPayloadConflictTest {

    private static final long AD_ID = 900002L;
    private static final long EVENT_TS = 1_760_000_000_000L;

    @Autowired
    private BillingReconConsumer consumer;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("同一 eventId 同一载荷重复投递：只落一条账单，两次都 ack")
    void duplicateSamePayload_isAppliedOnce() throws Exception {
        String eventId = eventId();
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> record = record(eventId, "0.02");

        consumer.onEvent(record, ack);
        consumer.onEvent(record, ack);

        assertEquals(1, billingRows(eventId));
        assertAmount("0.02", billingPrice(eventId));
        verify(ack, times(2)).acknowledge();
    }

    @Test
    @DisplayName("同一 eventId 不同载荷：抛冲突异常，已有金额不被改写")
    void differentPayloadForSameEventId_isRejected() throws Exception {
        String eventId = eventId();
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onEvent(record(eventId, "0.02"), ack);

        assertThrows(BidEventPayloadConflictException.class,
                () -> consumer.onEvent(record(eventId, "9.99"), ack));

        assertEquals(1, billingRows(eventId));
        assertAmount("0.02", billingPrice(eventId));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("processed_event 过保留期后迟到重放：billing_event 的指纹仍能拒绝改写金额")
    void lateReplayAfterDedupExpiry_cannotRewriteAmount() throws Exception {
        String eventId = eventId();
        consumer.onEvent(record(eventId, "0.02"), mock(Acknowledgment.class));

        // 模拟去重记录已过保留期被清理：下一次投递会重新走 handle()
        jdbc.update("DELETE FROM processed_event WHERE event_id = ? AND grp = ?",
                eventId, "billing-recon");

        assertThrows(BidEventPayloadConflictException.class,
                () -> consumer.onEvent(record(eventId, "9.99"), mock(Acknowledgment.class)));

        assertEquals(1, billingRows(eventId));
        assertAmount("0.02", billingPrice(eventId));
    }

    @Test
    @DisplayName("旧账单 payload_hash 为空：回填本次指纹，后续重放可被识别")
    void legacyBillingRowWithoutHash_isBackfilled() throws Exception {
        String eventId = eventId();
        jdbc.update("INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts, payload_hash) "
                        + "VALUES(?,?,?,?,?,NULL)",
                eventId, AD_ID, (int) AD_ID, new BigDecimal("0.02"), EVENT_TS);

        consumer.onEvent(record(eventId, "0.02"), mock(Acknowledgment.class));

        String hash = jdbc.queryForObject(
                "SELECT payload_hash FROM billing_event WHERE event_id = ?", String.class, eventId);
        assertNotNull(hash, "旧账单应在首次消费时回填指纹");
        assertEquals(64, hash.length());
    }

    @Test
    @DisplayName("并发重复投递：唯一键 + 指纹一致，只落一条账单且不误报冲突")
    void concurrentDuplicates_onlyOneBillingRow() throws Exception {
        String eventId = eventId();
        ConsumerRecord<String, String> record = record(eventId, "0.02");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Acknowledgment ack = mock(Acknowledgment.class);
                futures.add(pool.submit(() -> {
                    start.await();
                    consumer.onEvent(record, ack);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, billingRows(eventId));
        assertAmount("0.02", billingPrice(eventId));
    }

    private ConsumerRecord<String, String> record(String eventId, String payPrice)
            throws JsonProcessingException {
        BidEvent event = BidEvent.builder()
                .eventId(eventId)
                .requestId("it-req-" + eventId)
                .ts(EVENT_TS)
                .region("440318")
                .adId(AD_ID)
                .advertiserId((int) AD_ID)
                .winBid(new BigDecimal("0.50"))
                .payPrice(new BigDecimal(payPrice))
                .fallback(false)
                .hasAd(true)
                .priceMode(1)
                .build();
        return new ConsumerRecord<>("bid_event", 0, 0L, String.valueOf(AD_ID),
                mapper.writeValueAsString(event));
    }

    private int billingRows(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM billing_event WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private BigDecimal billingPrice(String eventId) {
        return jdbc.queryForObject(
                "SELECT pay_price FROM billing_event WHERE event_id = ?", BigDecimal.class, eventId);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0,
                () -> "金额被改写：期望 " + expected + "，实际 " + actual);
    }

    private static String eventId() {
        return "it-evt-" + UUID.randomUUID();
    }
}
