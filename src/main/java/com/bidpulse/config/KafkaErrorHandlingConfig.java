package com.bidpulse.config;

import com.bidpulse.infra.kafka.InvalidBidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费端错误处理：退避重试 → 死信主题（DLT）。
 *
 * <p><b>为什么必须显式配置</b>：不配置时框架使用默认错误处理器，重试若干次后
 * <b>直接跳过</b>失败记录 —— 对计费事件来说等同于静默丢钱，与「计费事件不可丢」
 * 的设计前提直接矛盾；而一条永远无法解析的坏消息又会被反复重试，拖住整个分区。
 *
 * <p>策略分两类：
 * <ul>
 *   <li><b>可重试故障</b>（DB 超时 / 连接异常）：500ms 退避、最多重试 3 次，仍失败投递 DLT；</li>
 *   <li><b>不可重试故障</b>（{@link InvalidBidEventException}：报文无法解析 / 缺少 eventId）：
 *       跳过重试，直接投递 DLT。</li>
 * </ul>
 *
 * <p>DLT 消息保留原 key/value，并带 {@code kafka_dlt-*} 头（原 topic / partition / offset），
 * 运维可按原分区键与位点排查、补偿。{@link DefaultErrorHandler} 默认
 * {@code isAckAfterHandle=true}：成功恢复（含投递 DLT）后容器会推进位点，
 * 因此 manual ack 模式下也不会无限重投同一条消息。
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 BidPulseProperties props) {
        // 死信目标可配置（默认 bid_event.DLT）；partition=-1 交给生产者按原 key 选择
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(props.getKafka().getDltTopic(), -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3));
        // 报文本身有问题：重试不会成功，跳过重试直接进 DLT
        handler.addNotRetryableExceptions(InvalidBidEventException.class);
        return handler;
    }
}
