package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 消费组：hourly-preagg（小时级预聚合）。
 *
 * <p>职责：按 {@code (stat_hour, ad_id)} 累加曝光数与消耗，落 {@code bid_stat_hourly}，
 * 作为大盘统计的数据源（避免每次查询都扫全量明细）。
 *
 * <p>幂等与位点语义由 {@link AbstractBidEventConsumer} 统一处理：
 * 先去重表，再 upsert 累加（重复投递只生效一次），事务提交后才 ack。
 */
@Component
public class HourlyPreaggConsumer extends AbstractBidEventConsumer {

    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    public HourlyPreaggConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper, "hourly-preagg");
    }

    @KafkaListener(groupId = "hourly-preagg", topics = "${bidpulse.kafka.topic:bid_event}")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        consume(rec, ack);
    }

    @Override
    protected void handle(BidEvent e) {
        // 空广告事件（bidpulse.fallback.enabled=false 时的 no-ad 结果）没有 ad_id。
        // bid_stat_hourly 以 (stat_hour, ad_id) 为主键且 ad_id NOT NULL，
        // 不跳过会让事件因 1048 反复失败、拖住整个分区。
        if (e.getAdId() == null) {
            return;
        }
        String hour = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(e.getTs()), ZoneId.systemDefault()).format(HOUR_FMT);
        BigDecimal spend = e.getPayPrice() != null ? e.getPayPrice() : BigDecimal.ZERO;
        jdbc.update(
                "INSERT INTO bid_stat_hourly(stat_hour, ad_id, impressions, spend) VALUES(?,?,1,?) "
                        + "ON DUPLICATE KEY UPDATE impressions=impressions+1, spend=spend+VALUES(spend)",
                hour, e.getAdId(), spend);
    }
}
