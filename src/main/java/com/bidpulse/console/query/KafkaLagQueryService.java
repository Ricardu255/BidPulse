package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.KafkaGroupView;
import com.bidpulse.console.dto.KafkaPartitionView;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Kafka 消费组 lag 查询。
 *
 * <p>从控制器里抽出来，概览页和异步运维页共用同一份口径。
 * 查询失败按 503 返回；消费组未注册返回 {@code exists=false}，不伪造 lag=0。
 */
@Service
@RequiredArgsConstructor
public class KafkaLagQueryService {

    /** 演示关注的三条旁路消费组。 */
    private static final List<String> GROUPS = List.of(
            "billing-recon", "hourly-preagg", "freq-antifraud");

    private static final int ADMIN_TIMEOUT_SEC = 5;

    private final AdminClient admin;
    private final BidPulseProperties props;

    public List<KafkaGroupView> groups() {
        List<KafkaGroupView> result = new ArrayList<>(GROUPS.size());
        for (String group : GROUPS) {
            result.add(loadGroup(group));
        }
        return result;
    }

    public long totalLag() {
        return groups().stream().mapToLong(KafkaGroupView::totalLag).sum();
    }

    /** 显式区分“组不存在”和“组存在但没有提交位点”。 */
    private boolean groupExists(String group) throws ExecutionException, InterruptedException, TimeoutException {
        try {
            admin.describeConsumerGroups(List.of(group)).all()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException) {
                return false;
            }
            throw e;
        }
    }

    private KafkaGroupView loadGroup(String group) {
        try {
            if (!groupExists(group)) {
                return new KafkaGroupView(group, false, 0, 0, List.of());
            }
            Map<TopicPartition, OffsetAndMetadata> allCommitted =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            // 消费组可能同时提交了 DLT / 内部主题的位点；控制台只关心业务主主题。
            String mainTopic = props.getKafka().getTopic();
            Map<TopicPartition, OffsetAndMetadata> committed = allCommitted.entrySet().stream()
                    .filter(entry -> mainTopic.equals(entry.getKey().topic()))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new));
            if (committed.isEmpty()) {
                return new KafkaGroupView(group, true, 0, 0, List.of());
            }

            Map<TopicPartition, OffsetSpec> specs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(specs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            List<KafkaPartitionView> partitions = new ArrayList<>(committed.size());
            long totalLag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committedOffset = entry.getValue().offset();
                ListOffsetsResult.ListOffsetsResultInfo end = ends.get(tp);
                long endOffset = end == null ? committedOffset : end.offset();
                long lag = Math.max(0, endOffset - committedOffset);
                totalLag += lag;
                partitions.add(new KafkaPartitionView(tp.partition(), committedOffset, endOffset, lag));
            }
            partitions.sort((a, b) -> Integer.compare(a.partition(), b.partition()));
            return new KafkaGroupView(group, true, partitions.size(), totalLag, partitions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.unavailable("查询消费组积压被中断: " + group);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException) {
                return new KafkaGroupView(group, false, 0, 0, List.of());
            }
            throw BizException.unavailable("查询消费组积压失败: " + group + " - " + e.getCause());
        } catch (TimeoutException e) {
            throw BizException.unavailable("查询消费组积压超时: " + group);
        }
    }
}
