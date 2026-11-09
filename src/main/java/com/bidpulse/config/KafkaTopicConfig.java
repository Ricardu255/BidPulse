package com.bidpulse.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * M6 主题声明：主主题 {@code bid_event} + 死信主题 {@code bid_event.DLT}。
 * 应用启动时由 Spring Kafka 的 {@code KafkaAdmin} 自动建主题，
 * 避免依赖 broker 的默认自动建主题（其分区数会退化为 1，无法体现按 adId 分区）。
 *
 * <p>监听器的 {@code topics} 仍用占位符 {@code ${bidpulse.kafka.topic}}：
 * 注解在 Bean 初始化前解析，拿不到 {@link BidPulseProperties}。
 *
 * <p>副本数由 {@code bidpulse.kafka.replicas} 控制（单节点 demo 默认 1）：
 * 设为 3 时自动追加 {@code min.insync.replicas=2}——只有「至少 2 个副本确认」才算写入成功，
 * 这样 {@code acks=all} 才有真正的多副本防丢语义；RF=1 时 all 与 1 等价。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic bidEventTopic(BidPulseProperties props) {
        return buildTopic(props, props.getKafka().getTopic());
    }

    /** 死信主题：消费重试耗尽或报文不可解析时的事件落点，供排查与补偿 */
    @Bean
    public NewTopic bidEventDltTopic(BidPulseProperties props) {
        return buildTopic(props, props.getKafka().getDltTopic());
    }

    /** 主主题与 DLT 共用同一套分区/副本策略，避免两个主题的可靠性配置漂移。 */
    static NewTopic buildTopic(BidPulseProperties props, String name) {
        BidPulseProperties.Kafka kafka = props.getKafka();
        TopicBuilder builder = TopicBuilder.name(name)
                .partitions(kafka.getPartitions())
                .replicas(kafka.getReplicas());
        if (kafka.getReplicas() >= 3) {
            builder.config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
        }
        return builder.build();
    }
}
