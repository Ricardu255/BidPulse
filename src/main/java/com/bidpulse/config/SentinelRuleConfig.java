package com.bidpulse.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.bidpulse.breaker.SentinelResources;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 熔断规则装载 —— <b>规则全部在代码内定义</b>。
 *
 * <p><b>为什么不接控制台（dashboard）</b>：
 * 控制台是独立进程，单机压测环境下要跟引擎抢 CPU / 内存，RT 数字会被污染；
 * 控制台上改的规则默认不持久化（重启即还原），要持久化得再引 Nacos / Apollo，
 * 对单实例 demo 是纯负担。规则随代码走版本控制，评审时和代码一起看更可靠。
 *
 * <p><b>为什么用「慢调用比例」而不是「异常比例 / 异常数」</b>：
 * 本项目的故障形态是「DB / Redis 变慢」而不是「快速报错」。
 * 异常比例模式下，一个「RT 打满 800ms 但最终成功」的调用完全不算失败，规则看不见它；
 * 慢调用比例直接按 RT 判定，语义天然对齐，也不需要在业务侧手工上报耗时。
 *
 * <p><b>两个单位坑</b>：{@code count} 在 RT 模式下是毫秒；
 * {@code timeWindow} 是<b>秒</b>，所以配置里的 {@code cooldown-ms} 要除以 1000。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "bidpulse.circuit-breaker.impl", havingValue = "sentinel", matchIfMissing = true)
public class SentinelRuleConfig {

    private final BidPulseProperties props;

    public SentinelRuleConfig(BidPulseProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void loadRules() {
        BidPulseProperties.CircuitBreakerSettings cb = props.getCircuitBreaker();
        int timeWindowSec = (int) Math.max(1, cb.getCooldownMs() / 1000);

        List<DegradeRule> rules = new ArrayList<>(2);
        rules.add(slowCallRule(SentinelResources.DB_CANDIDATES, cb.getDbSlowRtMs(), timeWindowSec, cb));
        rules.add(slowCallRule(SentinelResources.REDIS_BUDGET, cb.getRedisSlowRtMs(), timeWindowSec, cb));

        // 覆盖式装载：本类是全进程唯一装载 DegradeRule 的地方
        DegradeRuleManager.loadRules(rules);

        log.info("Sentinel 熔断规则已装载（慢调用比例模式）：" +
                        "DB 资源 {} 慢调用阈值 {}ms / Redis 资源 {} 阈值 {}ms；" +
                        "慢调用比例 >{}，最小请求数 {}，统计窗口 {}ms，熔断时长 {}s",
                SentinelResources.DB_CANDIDATES, cb.getDbSlowRtMs(),
                SentinelResources.REDIS_BUDGET, cb.getRedisSlowRtMs(),
                cb.getSlowRatio(), cb.getMinRequestAmount(), cb.getStatIntervalMs(), timeWindowSec);
    }

    /** 构造一条「慢调用比例」熔断规则 */
    private DegradeRule slowCallRule(String resource, double slowRtMs, int timeWindowSec,
                                     BidPulseProperties.CircuitBreakerSettings cb) {
        DegradeRule rule = new DegradeRule(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_RT);        // RT = 慢调用比例模式
        rule.setCount(slowRtMs);                             // 慢调用判定阈值（ms）
        rule.setSlowRatioThreshold(cb.getSlowRatio());        // 慢调用比例阈值
        rule.setMinRequestAmount(cb.getMinRequestAmount());   // 最小请求数
        rule.setStatIntervalMs(cb.getStatIntervalMs());       // 统计窗口（ms）
        rule.setTimeWindow(timeWindowSec);                    // 熔断持续时长（秒）
        return rule;
    }
}
