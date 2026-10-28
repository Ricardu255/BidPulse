package com.bidpulse.config;

import org.apache.kafka.clients.admin.NewTopic;
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
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic bidEventTopic(BidPulseProperties props) {
        // 单节点 demo：replicas=1。生产环境应改为 RF=3 + min.insync.replicas=2
        return TopicBuilder.name(props.getKafka().getTopic())
                .partitions(props.getKafka().getPartitions())
                .replicas(1)
                .build();
    }

    /** 死信主题：消费重试耗尽或报文不可解析时的事件落点，供排查与补偿 */
    @Bean
    public NewTopic bidEventDltTopic(BidPulseProperties props) {
        return TopicBuilder.name(props.getKafka().getDltTopic())
                .partitions(props.getKafka().getPartitions())
                .replicas(1)
                .build();
    }
}
