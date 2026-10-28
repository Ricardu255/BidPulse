package com.bidpulse.api.controller;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * M6 消费组管理接口（演示第 6 步用）。
 *
 * <ul>
 *   <li>{@code POST /admin/consumer/pause} —— 暂停全部监听容器（模拟消费端故障）；</li>
 *   <li>{@code POST /admin/consumer/resume} —— 恢复全部监听容器；</li>
 *   <li>{@code GET /admin/kafka/lag?group=xxx} —— 查某消费组的积压（lag）分布，供大盘曲线。</li>
 * </ul>
 *
 * <p>暂停期间 {@code /bid} 主链路完全不受影响，事件在 broker 中堆积、lag 爬升，
 * 恢复后消费组从已提交位点继续重放——这正是"旁路解耦"的直观证据。
 */
@RestController
@RequestMapping("/admin")
public class ConsumerAdminController {

    /** 管理端查询 Kafka 的超时（秒）：不跟随业务链路无限等待 */
    private static final int ADMIN_TIMEOUT_SEC = 5;

    private final KafkaListenerEndpointRegistry registry;
    private final AdminClient admin;

    public ConsumerAdminController(KafkaListenerEndpointRegistry registry, AdminClient admin) {
        this.registry = registry;
        this.admin = admin;
    }

    @PostMapping("/consumer/pause")
    public ApiResponse<Map<String, String>> pause() {
        registry.getAllListenerContainers().forEach(c -> c.pause());
        return ApiResponse.ok(Map.of("status", "paused"));
    }

    @PostMapping("/consumer/resume")
    public ApiResponse<Map<String, String>> resume() {
        registry.getAllListenerContainers().forEach(c -> c.resume());
        return ApiResponse.ok(Map.of("status", "resumed"));
    }

    /**
     * 查询某消费组的积压分布。
     *
     * <p>{@link AdminClient} 是单例 Bean（见 {@code KafkaAdminClientConfig}），不再按请求创建；
     * 组不存在返回 404，Kafka 查询失败/超时返回 503，均走统一错误结构，而不是裸 500。
     */
    @GetMapping("/kafka/lag")
    public ApiResponse<List<Map<String, Object>>> lag(@RequestParam String group) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (committed.isEmpty()) {
                // 组存在但尚无提交位点（或 broker 对未知组返回空表）：返回空列表，不当作错误
                return ApiResponse.ok(List.of());
            }
            Set<TopicPartition> tps = committed.keySet();
            Map<TopicPartition, OffsetSpec> specs = tps.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(specs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            List<Map<String, Object>> out = new ArrayList<>();
            for (TopicPartition tp : tps) {
                long committedOff = committed.get(tp).offset();
                long endOff = ends.get(tp).offset();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("topic", tp.topic());
                row.put("partition", tp.partition());
                row.put("committed", committedOff);
                row.put("end", endOff);
                row.put("lag", endOff - committedOff);
                out.add(row);
            }
            return ApiResponse.ok(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.unavailable("查询消费组积压被中断: " + group);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException) {
                throw BizException.notFound("消费组不存在: " + group);
            }
            throw BizException.unavailable("查询消费组积压失败: " + e.getCause());
        } catch (TimeoutException e) {
            throw BizException.unavailable("查询消费组积压超时: " + group);
        }
    }
}
