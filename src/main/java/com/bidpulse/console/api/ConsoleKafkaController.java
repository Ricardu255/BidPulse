package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.command.IdempotencyService;
import com.bidpulse.infra.kafka.dto.KafkaGroupView;
import com.bidpulse.infra.kafka.KafkaLagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 控制台异步运维：消费组积压查询、暂停/恢复。
 */
@RestController
@RequestMapping("/console-api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleKafkaController {

    private final KafkaLagQueryService kafkaLagQueryService;
    private final KafkaListenerEndpointRegistry registry;
    private final BidPulseProperties props;
    private final IdempotencyService idempotencyService;

    @GetMapping("/kafka/groups")
    public ApiResponse<List<KafkaGroupView>> groups() {
        return ApiResponse.ok(kafkaLagQueryService.groups());
    }

    @PostMapping("/kafka/pause")
    public ApiResponse<Map<String, String>> pause(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireWrite();
        idempotencyService.requireFresh("kafka-pause", idempotencyKey);
        registry.getAllListenerContainers().forEach(container -> container.pause());
        return ApiResponse.ok(Map.of("status", "paused"));
    }

    @PostMapping("/kafka/resume")
    public ApiResponse<Map<String, String>> resume(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireWrite();
        idempotencyService.requireFresh("kafka-resume", idempotencyKey);
        registry.getAllListenerContainers().forEach(container -> container.resume());
        return ApiResponse.ok(Map.of("status", "resumed"));
    }

    private void requireWrite() {
        if (!props.getConsole().isAllowWrite()) {
            throw BizException.forbidden("控制台写操作已关闭（bidpulse.console.allow-write=false）");
        }
    }
}
