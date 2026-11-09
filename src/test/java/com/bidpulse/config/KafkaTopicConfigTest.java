package com.bidpulse.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题可靠性配置单测：副本数可配，且 RF=3 时自动带 {@code min.insync.replicas=2}。
 *
 * <p>为什么这条规则不能只写在注释里：RF=1 时误设 min.insync.replicas=2 会让生产端
 * 永远抛 {@code NotEnoughReplicas}；反过来 RF=3 却不设 min ISR，{@code acks=all}
 * 只在单副本落盘时也可能返回成功，防丢语义名不副实。
 */
class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig();

    @Test
    void singleReplicaTopic_doesNotSetMinInsyncReplicas() {
        NewTopic topic = config.bidEventTopic(new BidPulseProperties());

        assertEquals("bid_event", topic.name());
        assertEquals(3, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
        assertTrue(topic.configs() == null || !topic.configs().containsKey("min.insync.replicas"),
                "单节点 demo 设 min.insync.replicas=2 会让 producer 直接失败");
    }

    @Test
    void threeReplicaTopics_setMinInsyncReplicasForDurability() {
        BidPulseProperties props = new BidPulseProperties();
        props.getKafka().setReplicas(3);

        NewTopic main = config.bidEventTopic(props);
        NewTopic dlt = config.bidEventDltTopic(props);

        assertEquals((short) 3, main.replicationFactor());
        assertEquals("2", main.configs().get("min.insync.replicas"));
        assertEquals("bid_event.DLT", dlt.name());
        assertEquals((short) 3, dlt.replicationFactor());
        assertEquals("2", dlt.configs().get("min.insync.replicas"),
                "主主题与 DLT 的可靠性配置必须一致");
    }
}
