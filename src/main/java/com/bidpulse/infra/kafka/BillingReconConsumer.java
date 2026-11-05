package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费组：billing-recon（计费对账）。
 *
 * <p>职责：把每条竞价事件的实扣金额（payPrice）落 {@code billing_event}，
 * 事后与 Redis 预算消耗对账，验证"扣的钱 = 该收的钱"。
 *
 * <p>去重、位点与事务顺序由 {@link AbstractBidEventConsumer} 统一处理：
 * 先 {@code INSERT IGNORE processed_event(event_id, 'billing-recon', payload_hash)}；
 * 指纹相同才跳过，指纹不同转 DLT。{@code billing_event} 也保存 payload_hash：
 * 即使去重记录已过保留期，迟到的重放也只能命中同一行，不能改写金额。
 * ack 在事务提交后推进（{@link AckAfterCommit}），提交失败不会丢事件。
 */
@Slf4j
@Component
public class BillingReconConsumer extends AbstractBidEventConsumer {

    public BillingReconConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper, "billing-recon");
    }

    @KafkaListener(groupId = "billing-recon", topics = "${bidpulse.kafka.topic:bid_event}")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        consume(rec, ack);
    }

    @Override
    protected void handle(BidEvent e, String payloadHash) {
        // 没有胜出广告就没有扣费，不必为一次空竞价落一条全 null 的计费流水
        if (e.getAdId() == null) {
            return;
        }
        // 不能再用 ON DUPLICATE KEY UPDATE pay_price=VALUES(pay_price)：
        // processed_event 过保留期后，迟到的重放会把已有账单金额静默改掉。
        // 这里只保证行存在（重复时 no-op），随后用 payload_hash 判断载荷是否一致。
        jdbc.update(
                "INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts, payload_hash) "
                        + "VALUES(?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE event_id=event_id",
                e.getEventId(),
                e.getAdId(),
                e.getAdvertiserId(),
                e.getPayPrice(),
                e.getTs(),
                payloadHash);

        String storedHash = jdbc.queryForObject(
                "SELECT payload_hash FROM billing_event WHERE event_id = ?",
                String.class, e.getEventId());
        if (storedHash == null) {
            // 上线前的旧账单没有指纹：回填本次载荷，后续重放即可被识别。
            jdbc.update(
                    "UPDATE billing_event SET payload_hash = ? "
                            + "WHERE event_id = ? AND payload_hash IS NULL",
                    payloadHash, e.getEventId());
            log.warn("billing_event 旧记录已回填 payload_hash: eventId={}", e.getEventId());
        } else if (!storedHash.equals(payloadHash)) {
            throw new BidEventPayloadConflictException(
                    "计费载荷冲突：eventId=" + e.getEventId()
                            + "。billing_event 已存在不同载荷，拒绝改写金额并转入 DLT。");
        }
    }
}
