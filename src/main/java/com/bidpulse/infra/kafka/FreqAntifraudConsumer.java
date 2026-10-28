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
 * 消费组：freq-antifraud（频次与反作弊）。
 *
 * <p>职责：按 {@code ad_id} 累计曝光频次，落 {@code ad_freq}，用于识别异常曝光行为。
 *
 * <p><b>维度简化说明（诚实标注）</b>：当前 {@code BidRequest} 无 userId，
 * 无法按"用户"维度统计频次，此处退而按 {@code ad_id} 维度计数（消费组/幂等/lag 机制完全真实，
 * 仅聚合维度简化）。真实生产应按 userId 维度。
 *
 * <p>幂等与位点语义由 {@link AbstractBidEventConsumer} 统一处理。
 */
@Component
public class FreqAntifraudConsumer extends AbstractBidEventConsumer {

    public FreqAntifraudConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper, "freq-antifraud");
    }

    @KafkaListener(groupId = "freq-antifraud", topics = "${bidpulse.kafka.topic:bid_event}")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        consume(rec, ack);
    }

    @Override
    protected void handle(BidEvent e) {
        // 空广告事件（bidpulse.fallback.enabled=false 时的 no-ad 结果）没有广告维度可计数；
        // ad_freq.ad_id 为 NOT NULL，不跳过会让消费组对该事件反复报错重试。
        if (e.getAdId() == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO ad_freq(ad_id, cnt) VALUES(?,1) ON DUPLICATE KEY UPDATE cnt=cnt+1",
                e.getAdId());
    }
}
