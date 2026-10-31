package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.job.DltReplayJob;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台单条 DLT 重放：定位 partition + offset，同步投递回主主题。
 *
 * <p>不提交 DLT 位点：单条重放不改变定时任务的分区进度，避免把中间未处理的消息跳过。
 * 已投递的消息与原 DLT 记录并存，重复消费由业务消费组 {@code processed_event} 幂等吸收。
 */
@Service
@RequiredArgsConstructor
public class DltReplayAdminService {

    private final KafkaProperties kafkaProperties;
    private final BidPulseProperties props;
    private final DltReplayJob dltReplayJob;

    public DltReplayJob.ReplayOutcome replayOne(int partition, long offset) {
        if (partition < 0 || offset < 0) {
            throw BizException.badRequest("partition / offset 不能为负数");
        }
        String topic = props.getKafka().getDltTopic();
        Map<String, Object> conf = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        conf.put(ConsumerConfig.GROUP_ID_CONFIG, "console-dlt-replay-one");
        conf.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(conf)) {
            TopicPartition tp = new TopicPartition(topic, partition);
            consumer.assign(List.of(tp));
            long beginning = consumer.beginningOffsets(List.of(tp)).getOrDefault(tp, 0L);
            long end = consumer.endOffsets(List.of(tp)).getOrDefault(tp, 0L);
            if (offset < beginning || offset >= end) {
                throw BizException.notFound("DLT 消息不存在: " + topic + "-" + partition + "@" + offset);
            }
            consumer.seek(tp, offset);

            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.partition() == partition && record.offset() == offset) {
                        return dltReplayJob.replayRecord(record);
                    }
                }
            }
            throw BizException.notFound("DLT 消息在 3s 内未拉到: " + topic + "-" + partition + "@" + offset);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.unavailable("单条 DLT 重放失败: " + e.getMessage());
        }
    }
}
