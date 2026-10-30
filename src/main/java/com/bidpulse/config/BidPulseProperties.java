package com.bidpulse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@code bidpulse.*} 配置的唯一入口。
 *
 * <p><b>为什么收口</b>：这些参数原来以 {@code @Value} 散落在
 * {@code BidService / BudgetService / BudgetKeyCleaner / CircuitBreakerConfig /
 * SentinelRuleConfig / KafkaTopicConfig / BidDetailBuffer} 七个类里，
 * 改一个默认值要翻七处，且单位（ms / s / 元 / 分）只能靠注释约束。
 * 集中成一个带类型的对象后，默认值、单位、嵌套关系都在一处，IDE 也能补全。
 *
 * <p>字段名与 {@code application.yml} 的 kebab-case 松绑定：
 * {@code candidate-limit → candidateLimit}、{@code total-ms → totalMs}。
 * 注意 {@code @KafkaListener} / {@code @Scheduled} 注解里的占位符仍然直接读配置，
 * 不能引用本对象（注解在 Bean 初始化前就要解析）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bidpulse")
public class BidPulseProperties {

    /** 二价最小加价（元） */
    private BigDecimal minIncrement = new BigDecimal("0.01");

    private Timeout timeout = new Timeout();
    private Bid bid = new Bid();
    private CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();
    private Fallback fallback = new Fallback();
    private Detail detail = new Detail();
    private Kafka kafka = new Kafka();
    private Budget budget = new Budget();
    private Frequency frequency = new Frequency();
    private Console console = new Console();

    @Data
    public static class Timeout {
        /** 整条链路总超时预算（ms） */
        private long totalMs = 1000;
    }

    @Data
    public static class Bid {
        /** 单次竞价的候选上限（SQL LIMIT），同时决定预算预热规模 */
        private int candidateLimit = 50;
    }

    /**
     * 熔断配置。
     *
     * <p>类名带 {@code Settings} 后缀是为了避开与 {@link com.bidpulse.breaker.CircuitBreaker}
     * （local 熔断实现）的同名冲突——两者同名时，同一文件里引用必须写全限定名，
     * 且 import 时极易选错。
     */
    @Data
    public static class CircuitBreakerSettings {
        /** sentinel = 慢调用比例熔断；local = 自写连续失败计数（对照基线） */
        private String impl = "sentinel";
        /** 熔断持续时长（ms）；Sentinel 只接受秒，装载时换算 */
        private long cooldownMs = 5000;
        /** local 实现：连续失败多少次打开 */
        private int failureThreshold = 5;
        /** Sentinel：慢调用比例阈值 */
        private double slowRatio = 0.5;
        /** Sentinel：统计窗口内最小请求数 */
        private int minRequestAmount = 5;
        /** Sentinel：统计窗口（ms） */
        private int statIntervalMs = 1000;
        /** DB 候选检索的慢调用判定阈值（ms） */
        private double dbSlowRtMs = 800;
        /** Redis 预算扣减的慢调用判定阈值（ms） */
        private double redisSlowRtMs = 100;
    }

    @Data
    public static class Fallback {
        /** 兜底总开关；false 仅用于 A/B 对照实验 */
        private boolean enabled = true;
        /** 强制兜底：复现「依赖全面故障」 */
        private boolean force = false;
        /** 兜底广告本地缓存时长（ms）：正常时最多这么久访问一次 Redis */
        private long cacheTtlMs = 5000;
        /** Redis 读取失败后的退避时长（ms）：期间直接使用本地缓存 */
        private long retryBackoffMs = 30000;
    }

    @Data
    public static class Detail {
        /** 测量专用：true 时退化为同步单条 INSERT（模拟 M1 基线） */
        private boolean syncInsert = false;
    }

    @Data
    public static class Kafka {
        private String topic = "bid_event";
        /** 死信主题：消费重试耗尽 / 报文不可解析的事件落点（KafkaErrorHandlingConfig） */
        private String dltTopic = "bid_event.DLT";
        private int partitions = 3;

        // ---- 消费端重试：指数退避，尽量在进入 DLT 前吸收瞬时 DB 抖动 ----
        /** 首次重试间隔（ms） */
        private long retryInitialMs = 1000;
        /** 退避倍数 */
        private double retryMultiplier = 2.0;
        /** 单次重试间隔上限（ms） */
        private long retryMaxMs = 16000;
        /** 最大重试次数；默认 1s→2s→4s→8s→16s，总窗口约 31s */
        private int retryMaxAttempts = 5;

        // ---- DLT 重放：把重试耗尽的事件延迟重新投回主主题 ----
        /** 是否启用 DLT 重放任务 */
        private boolean dltReplayEnabled = true;
        /** 重放任务 cron，默认每 5 分钟 */
        private String dltReplayCron = "0 */5 * * * ?";
        /** 单次最多处理的消息数，避免一次调度占用过久 */
        private int dltReplayMaxRecords = 1000;
        /** 单次调度最长运行时长（ms） */
        private long dltReplayMaxDurationMs = 10000;
        /** 同一事件最多重放次数；超过后留在 DLT 等待人工处理 */
        private int dltReplayMaxAttempts = 3;
        /** 重放投递主主题的同步等待超时（ms）；超时则不提交 DLT 位点，下次重试 */
        private long dltReplaySendTimeoutMs = 5000;
        /** processed_event 保留天数：必须不短于 Kafka 消息保留期，否则重放时去重失效 */
        private int processedEventRetentionDays = 8;
        /** 单次清理最多删除行数，避免长事务与长时间锁表 */
        private int processedEventCleanupBatch = 10000;
        /** 单次调度最多删除的行数上限，避免维护窗口被一次清理占满 */
        private int processedEventCleanupMaxPerRun = 1000000;
        /** 去重表清理任务 cron */
        private String processedEventCleanupCron = "0 30 2 * * ?";
    }

    @Data
    public static class Budget {
        /** 播种 key 的 TTL（秒） */
        private long keyTtlSeconds = 172800;
        private Cleanup cleanup = new Cleanup();
        private Reset reset = new Reset();
        private Recon recon = new Recon();

        @Data
        public static class Cleanup {
            /** 保留「今天」往前共 N 天的日期分桶 */
            private int retentionDays = 2;
            private String cron = "0 0 1 * * ?";
        }

        @Data
        public static class Reset {
            /** 日预算复位（status 3 -> 1）的触发时间 */
            private String cron = "0 5 0 * * ?";
        }

        @Data
        public static class Recon {
            /** 是否启用预算对账任务 */
            private boolean enabled = true;
            /** 对账 cron，默认每小时第 15 分钟 */
            private String cron = "0 15 * * * ?";
            /** 允许的误差（分）：Redis 与 DB 账单差值不超过该值视为正常（事件在途） */
            private long toleranceCents = 1;
            /** 单批处理的广告数，控制 IN 查询与 Redis 读取规模 */
            private int batchSize = 500;
        }
    }

    /**
     * 实时频次控制。
     *
     * <p>按 (userId, adId) 的自然小时固定窗口计数：同一用户对同一广告每小时最多成交
     * {@code maxPerHour} 次。计数键 {@code freq:u:{userId}:{yyyyMMddHH}} 是 Hash，
     * field 为 adId，一次 {@code HINCRBY} 只影响当前广告。
     *
     * <p>关闭后不读写任何频次 key，热路径与未引入该功能时完全一致。
     */
    @Data
    public static class Frequency {
        private boolean enabled = true;
        /** 同一用户对同一广告每小时的最大成交次数 */
        private int maxPerHour = 3;
        /**
         * 频次 key 的 TTL（秒），默认 2 小时。
         *
         * <p>固定窗口键按小时命名，旧窗口不会再被读取；TTL 只负责回收。
         * 取 2 小时给跨小时边界留出冗余，避免窗口末尾写入的键过早消失。
         */
        private long keyTtlSeconds = 7200;
    }

    /**
     * 本地演示控制台配置。
     *
     * <p>控制台本身只是现有服务的只读聚合 + 少量运维动作，不承载业务逻辑。
     * 默认关闭，只有在显式打开 {@code bidpulse.console.enabled=true} 时才注册接口；
     * 写操作（暂停/恢复消费）再由 {@code allow-write} 单独控制。
     */
    @Data
    public static class Console {
        /** 是否注册 /console-api/v1 接口 */
        private boolean enabled = false;
        /** 是否允许暂停/恢复消费等写操作；只读查询不受影响 */
        private boolean allowWrite = false;
        /**
         * 前后端分离部署时允许的前端来源，逗号分隔。
         * 开发态默认放行 Vite（5173）；同源部署或使用 Vite 代理时不会触发 CORS。
         */
        private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
    }
}
