package com.bidpulse.console.dto;

import java.util.List;

/**
 * 一个消费组的积压汇总。
 *
 * @param exists        消费组是否已在 broker 注册；false 时 lag 为 0，不代表健康
 * @param partitionCount 已提交位点的分区数
 * @param totalLag      所有分区 lag 之和
 * @param partitions    分区明细
 */
public record KafkaGroupView(
        String group,
        boolean exists,
        int partitionCount,
        long totalLag,
        List<KafkaPartitionView> partitions) {
}
