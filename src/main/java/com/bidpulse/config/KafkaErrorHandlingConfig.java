package com.bidpulse.config;

import com.bidpulse.infra.kafka.InvalidBidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka 消费端错误处理：退避重试 → 死信主题（DLT）。
 *
 * <p><b>为什么必须显式配置</b>：不配置时框架使用默认错误处理器，重试若干次后
 * <b>直接跳过</b>失败记录 —— 对计费事件来说等同于静默丢钱，与「计费事件不可丢」
 * 的设计前提直接矛盾；而一条永远无法解析的坏消息又会被反复重试，拖住整个分区。
 *
 * <p>策略分两类：
 * <ul>
 *   <li><b>可重试故障</b>（DB 超时 / 连接异常）：固定 500ms × 3 次会留出一个尴尬的空档——
 *       500~1500ms 恰好覆盖不住一次稍长的 DB 抖动（如主从切换、连接池重建），事件直接落 DLT；
 *       现在改为指数退避 1s → 2s → 4s → 8s → 16s（总窗口约 31s），把瞬时故障吸收在 DLT 之前；</li>
 *   <li><b>不可重试故障</b>（{@link InvalidBidEventException}：报文无法解析 / 缺少 eventId）：
 *       跳过重试，直接投递 DLT。</li>
 * </ul>
 *
 * <p>DLT 消息保留原 key/value，并带 {@code kafka_dlt-*} 头（原 topic / partition / offset），
 * 运维可按原分区键与位点排查、补偿。{@link DefaultErrorHandler} 默认
 * {@code isAckAfterHandle=true}：成功恢复（含投递 DLT）后容器会推进位点，
 * 因此 manual ack 模式下也不会无限重投同一条消息。
 *
 * <p>进入 DLT 不等于终结：{@code DltReplayJob} 会周期性把可解析、未超过重放上限的事件
 * 重新投回主主题，由各消费组按 {@code processed_event} 幂等消费。
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 BidPulseProperties props) {
        BidPulseProperties.Kafka cfg = props.getKafka();
        // 死信目标可配置（默认 bid_event.DLT）；partition=-1 交给生产者按原 key 选择
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(cfg.getDltTopic(), -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, retryBackOff(cfg));
        // 报文本身有问题：重试不会成功，跳过重试直接进 DLT
        handler.addNotRetryableExceptions(InvalidBidEventException.class);
        return handler;
    }

    /**
     * 构造指数退避策略。
     *
     * <p>独立成包级方法是为了可单测：{@link DefaultErrorHandler} 没有暴露 backOff 的取值接口，
     * 只能通过反射挖内部字段（字段属于 spring-kafka 实现细节，升级易碎）。
     * 这里直接断言策略本身，避免为测试引入反射。
     *
     * <p>{@code maxRetries} 是「首投之后」的重试次数：默认 5 次，
     * 即 1s → 2s → 4s → 8s → 16s，首次投递 + 5 次重试都失败后第 6 次进入 DLT。
     */
    static ExponentialBackOffWithMaxRetries retryBackOff(BidPulseProperties.Kafka cfg) {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(Math.max(1, cfg.getRetryMaxAttempts()));
        backOff.setInitialInterval(Math.max(1, cfg.getRetryInitialMs()));
        backOff.setMultiplier(Math.max(1.0, cfg.getRetryMultiplier()));
        backOff.setMaxInterval(Math.max(1, cfg.getRetryMaxMs()));
        return backOff;
    }
}
