package com.bidpulse.console.dto;

/**
 * 单个 Kafka 分区的位点与积压。
 */
public record KafkaPartitionView(
        int partition,
        long committed,
        long end,
        long lag) {
}
