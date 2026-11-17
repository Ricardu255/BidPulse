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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

        List<String> stored = jdbc.query(
                "SELECT payload_hash FROM billing_event WHERE event_id = ?",
                (rs, i) -> rs.getString(1), e.getEventId());
        String storedHash = stored.isEmpty() ? null : stored.get(0);
        if (storedHash == null) {
            // 上线前的旧账单没有指纹。这里不能拿「先到者」的载荷去盖章：
            // 上面的 INSERT 在重复时是 no-op，行里的金额是**另一次**载荷写下的，
            // 盖上去会让指纹与内容永久不符，未来重放原始载荷还会被误判成冲突进 DLT。
            // 正确做法是拿存量业务列与本次载荷比对：一致才回填，不一致按冲突转人工。
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT ad_id, pay_price, ts FROM billing_event WHERE event_id = ?", e.getEventId());
            if (matchesStored(row, e)) {
                int backfilled = jdbc.update(
                        "UPDATE billing_event SET payload_hash = ? "
                                + "WHERE event_id = ? AND payload_hash IS NULL",
                        payloadHash, e.getEventId());
                if (backfilled > 0) {
                    log.warn("billing_event 旧记录已回填 payload_hash: eventId={}", e.getEventId());
                }
            } else {
                throw new BidEventPayloadConflictException(
                        "计费载荷冲突：eventId=" + e.getEventId()
                                + "。旧账单无指纹且业务字段与本次载荷不一致，需人工核对后处理。");
            }
        } else if (!storedHash.equals(payloadHash)) {
            throw new BidEventPayloadConflictException(
                    "计费载荷冲突：eventId=" + e.getEventId()
                            + "。billing_event 已存在不同载荷，拒绝改写金额并转入 DLT。");
        }
    }

    /**
     * 存量账单（无指纹）与本次载荷的业务字段是否一致。
     *
     * <p>比较 {@code ad_id / pay_price / ts} 三列即可：它们由载荷直接映射，且重复投递时
     * INSERT 是 no-op，所以这三列就是「当初写下这行的那份载荷」的等价物。
     */
    private static boolean matchesStored(Map<String, Object> row, BidEvent e) {
        Object adId = row.get("ad_id");
        Object payPrice = row.get("pay_price");
        Object ts = row.get("ts");
        if (!(adId instanceof Number) || !(ts instanceof Number)) {
            return false;
        }
        if (((Number) adId).longValue() != e.getAdId().longValue()
                || ((Number) ts).longValue() != e.getTs()) {
            return false;
        }
        if (!(payPrice instanceof BigDecimal)) {
            return false;
        }
        return e.getPayPrice() != null
                && ((BigDecimal) payPrice).compareTo(e.getPayPrice()) == 0;
    }
}
