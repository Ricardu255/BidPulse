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
 * <p><b>重复与冲突必须分开</b>：{@code (event_id, grp)} 已存在时，继续比对
 * {@code payload_hash}——
 * <ul>
 *   <li>指纹相同：正常重复投递，ack 跳过；</li>
 *   <li>指纹不同：同一个 eventId 对应了两份载荷，抛 {@link BidEventPayloadConflictException}
 *       转 DLT，绝不静默丢弃或覆盖；</li>
 *   <li>指纹为空：上线前写入的旧记录，回填本次指纹后按重复处理，避免升级窗口内误报。</li>
 * </ul>
 *
 * <p><b>错误分类</b>：报文解析失败 / 缺少 {@code eventId} / 载荷冲突抛
 * {@link InvalidBidEventException}，在错误处理器里登记为不可重试，直接投递死信主题；
 * 业务或 DB 异常抛 {@code RuntimeException}，退避重试后再进 DLT
 * （见 {@code KafkaErrorHandlingConfig}）。
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

    /** 模板方法：解析 → 去重/冲突检测 → 子类业务 → 提交后 ack */
    protected final void consume(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        BidEvent event = parse(rec);
        String payloadHash = BidEventPayloadHash.sha256Hex(event);
        try {
            int inserted = jdbc.update(
                    "INSERT IGNORE INTO processed_event(event_id, grp, payload_hash) VALUES(?, ?, ?)",
                    event.getEventId(), group, payloadHash);
            if (inserted == 0) {
                String existingHash = jdbc.queryForObject(
                        "SELECT payload_hash FROM processed_event WHERE event_id = ? AND grp = ?",
                        String.class, event.getEventId(), group);
                if (existingHash == null) {
                    // 上线前写入的旧记录：没有原始指纹可比对。回填本次指纹，
                    // 让后续重复投递至少能被识别；不把升级窗口内的正常重投误报成冲突。
                    jdbc.update(
                            "UPDATE processed_event SET payload_hash = ? "
                                    + "WHERE event_id = ? AND grp = ? AND payload_hash IS NULL",
                            payloadHash, event.getEventId(), group);
                    log.warn("{} 命中旧版去重记录，已回填 payload_hash: eventId={}",
                            group, event.getEventId());
                } else if (!existingHash.equals(payloadHash)) {
                    throw new BidEventPayloadConflictException(
                            "事件载荷冲突：eventId=" + event.getEventId() + ", grp=" + group
                                    + "。同一 eventId 对应了不同载荷，拒绝处理并转入 DLT。");
                }
                // 指纹相同（或旧记录已回填）：正常重复投递，放行位点，不重复执行业务
                AckAfterCommit.ack(ack);
                return;
            }
            handle(event, payloadHash);
            AckAfterCommit.ack(ack);
        } catch (InvalidBidEventException ex) {
            // 报文/载荷冲突属于永久错误：保持异常类型，让错误处理器直接投 DLT，不做无谓重试。
            log.error("{} 检测到不可重试事件（转 DLT）event={}: {}",
                    group, rec.value(), ex.getMessage());
            throw ex;
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

    /**
     * 本组自己的业务写；异常由模板统一处理（回滚 + 不 ack）。
     *
     * @param payloadHash 已校验过的载荷指纹，供需要自证幂等的表（如 billing_event）落库
     */
    protected abstract void handle(BidEvent event, String payloadHash);
}
