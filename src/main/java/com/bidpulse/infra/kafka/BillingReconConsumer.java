package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
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
 * 先 {@code INSERT IGNORE processed_event(event_id, 'billing-recon')}，影响行数=0 即跳过；
 * {@code billing_event} 以 event_id 为唯一键，重复投递只更新、不产生重复账单；
 * ack 在事务提交后推进（{@link AckAfterCommit}），提交失败不会丢事件。
 */
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
    protected void handle(BidEvent e) {
        // 没有胜出广告就没有扣费，不必为一次空竞价落一条全 null 的计费流水
        if (e.getAdId() == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO billing_event(event_id, ad_id, advertiser_id, pay_price, ts) "
                        + "VALUES(?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE pay_price=VALUES(pay_price)",
                e.getEventId(),
                e.getAdId(),
                e.getAdvertiserId(),
                e.getPayPrice(),
                e.getTs());
    }
}
