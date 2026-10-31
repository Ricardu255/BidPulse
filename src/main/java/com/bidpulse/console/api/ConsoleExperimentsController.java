package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.ExperimentsView;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台实验开关：只暴露运行时安全、可即时生效的配置。
 *
 * <p>不暴露 Redis/Kafka 连接参数，也不暴露熔断实现切换（需要重建 Bean）。
 */
@RestController
@RequestMapping("/console-api/v1/experiments")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleExperimentsController {

    private final BidPulseProperties props;

    @GetMapping
    public ApiResponse<ExperimentsView> get() {
        return ApiResponse.ok(snapshot());
    }

    @PutMapping
    public ApiResponse<ExperimentsView> update(@RequestBody ExperimentsUpdate update) {
        requireWrite();
        // 先校验所有字段，再统一应用，避免“前面的开关已改、后面的字段校验失败”的半更新。
        Integer max = update.frequencyMaxPerHour();
        if (max != null && (max < 1 || max > 100)) {
            throw BizException.badRequest("frequencyMaxPerHour 应在 1~100 之间");
        }
        if (update.fallbackEnabled() != null) {
            props.getFallback().setEnabled(update.fallbackEnabled());
        }
        if (update.fallbackForce() != null) {
            props.getFallback().setForce(update.fallbackForce());
        }
        if (update.frequencyEnabled() != null) {
            props.getFrequency().setEnabled(update.frequencyEnabled());
        }
        if (max != null) {
            props.getFrequency().setMaxPerHour(max);
        }
        return ApiResponse.ok(snapshot());
    }

    private ExperimentsView snapshot() {
        return new ExperimentsView(
                props.getFallback().isEnabled(),
                props.getFallback().isForce(),
                props.getFrequency().isEnabled(),
                props.getFrequency().getMaxPerHour(),
                props.getDetail().isSyncInsert(),
                props.getConsole().isAllowWrite());
    }

    private void requireWrite() {
        if (!props.getConsole().isAllowWrite()) {
            throw BizException.forbidden("控制台写操作已关闭（bidpulse.console.allow-write=false）");
        }
    }

    /** 只允许非 null 字段更新；null 表示不动。 */
    public record ExperimentsUpdate(
            Boolean fallbackEnabled,
            Boolean fallbackForce,
            Boolean frequencyEnabled,
            Integer frequencyMaxPerHour) {
    }
}
