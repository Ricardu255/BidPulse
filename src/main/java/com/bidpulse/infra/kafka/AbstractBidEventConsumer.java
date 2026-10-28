package com.bidpulse.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidpulse.event.BidEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * 竞价事件消费模板：<b>解析 → 幂等去重 → 业务写 → 提交后 ack</b>。
 *
 * <p><b>为什么抽这个基类</b>：三个消费组原来各写一遍完全相同的去重/ack/try-catch，
 * 结果「ack 放在事务提交前」这类问题要改三处、也可能只改两处。
 * 模板把流程固定下来，子类只实现「这条事件对本组意味着什么」。
 *
 * <p><b>位点语义</b>：ack 统一走 {@link AckAfterCommit}——数据库事务提交成功才推进位点；
 * 回滚时不 ack，Kafka 重投，由 {@code processed_event} 的 {@code (event_id, grp)} 幂等拦截。
 *
 * <p><b>错误分类</b>：报文解析失败 / 缺少 {@code eventId} 抛 {@link InvalidBidEventException}，
 * 在错误处理器里登记为不可重试，直接投递死信主题；业务或 DB 异常抛 {@code RuntimeException}，
 * 退避重试后再进 DLT（见 {@code KafkaErrorHandlingConfig}）。
 *
 * <p>子类的 {@code @KafkaListener} 方法只需一行 {@code consume(rec, ack)}，
 * 事务注解仍留在子类方法上，保证 Spring 代理拦到的是公开监听方法。
 */
@Slf4j
public abstract class AbstractBidEventConsumer {

    protected final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String group;

    protected AbstractBidEventConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper, String group) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.group = group;
    }

    /** 模板方法：解析 → 去重 → 子类业务 → 提交后 ack */
    protected final void consume(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        BidEvent event = parse(rec);
        try {
            int inserted = jdbc.update(
                    "INSERT IGNORE INTO processed_event(event_id, grp) VALUES(?, ?)",
                    event.getEventId(), group);
            if (inserted == 0) {
                // 本组已处理过：直接放行位点，不重复执行业务
                AckAfterCommit.ack(ack);
                return;
            }
            handle(event);
            AckAfterCommit.ack(ack);
        } catch (Exception ex) {
            log.error("{} 处理失败（未 ack，将重投）event={}: {}", group, rec.value(), ex.getMessage());
            // 重新抛出：触发 @Transactional 回滚，去重标记与业务写同进同退；不 ack → 重投重试
            throw new RuntimeException(ex);
        }
    }

    /**
     * 解析报文并校验幂等键。
     *
     * <p>失败抛 {@link InvalidBidEventException}：这类消息重试永远不会成功，
     * 由错误处理器跳过重试直接投递 DLT，避免一条坏消息把分区卡住。
     */
    private BidEvent parse(ConsumerRecord<String, String> rec) {
        BidEvent event;
        try {
            event = objectMapper.readValue(rec.value(), BidEvent.class);
        } catch (Exception parseEx) {
            log.error("{} 收到无法解析的竞价事件（直接投递 DLT）: {}", group, parseEx.getMessage());
            throw new InvalidBidEventException("无法解析竞价事件: " + parseEx.getMessage(), parseEx);
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            log.error("{} 收到缺少 eventId 的竞价事件（直接投递 DLT）: {}", group, rec.value());
            throw new InvalidBidEventException("竞价事件缺少 eventId，无法做幂等去重");
        }
        return event;
    }

    /** 本组自己的业务写；异常由模板统一处理（回滚 + 不 ack） */
    protected abstract void handle(BidEvent event);
}
