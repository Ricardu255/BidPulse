package com.bidpulse.api.controller;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.infra.kafka.dto.KafkaGroupView;
import com.bidpulse.infra.kafka.KafkaLagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M6 消费组管理接口。
 *
 * <ul>
 *   <li>{@code POST /admin/consumer/pause} —— 暂停全部监听容器（模拟消费端故障）；</li>
 *   <li>{@code POST /admin/consumer/resume} —— 恢复全部监听容器；</li>
 *   <li>{@code GET /admin/kafka/lag?group=xxx} —— 查某消费组的积压。</li>
 * </ul>
 *
 * <p>lag 计算统一委托给 {@link KafkaLagQueryService}，与
 * {@code /console-api/v1/kafka/groups} 使用同一口径（只统计业务主主题）。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ConsumerAdminController {

    private final KafkaListenerEndpointRegistry registry;
    private final KafkaLagQueryService kafkaLagQueryService;
    private final BidPulseProperties props;

    @PostMapping("/consumer/pause")
    public ApiResponse<Map<String, String>> pause() {
        registry.getAllListenerContainers().forEach(container -> container.pause());
        return ApiResponse.ok(Map.of("status", "paused"));
    }

    @PostMapping("/consumer/resume")
    public ApiResponse<Map<String, String>> resume() {
        registry.getAllListenerContainers().forEach(container -> container.resume());
        return ApiResponse.ok(Map.of("status", "resumed"));
    }

    /**
     * 查询某消费组的积压分布。
     *
     * <p>组不存在返回 404；Kafka 查询失败/超时由 service 统一转 503。
     */
    @GetMapping("/kafka/lag")
    public ApiResponse<List<Map<String, Object>>> lag(@RequestParam String group) {
        KafkaGroupView view = kafkaLagQueryService.group(group);
        if (!view.exists()) {
            throw BizException.notFound("消费组不存在: " + group);
        }
        String topic = props.getKafka().getTopic();
        List<Map<String, Object>> out = view.partitions().stream().map(partition -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", topic);
            row.put("partition", partition.partition());
            row.put("committed", partition.committed());
            row.put("end", partition.end());
            row.put("lag", partition.lag());
            return row;
        }).toList();
        return ApiResponse.ok(out);
    }
}
