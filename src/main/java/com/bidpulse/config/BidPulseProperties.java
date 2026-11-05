package com.bidpulse.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
@Validated
@ConfigurationProperties(prefix = "bidpulse")
public class BidPulseProperties {

    /** 二价最小加价（元） */
    @DecimalMin(value = "0.0001", message = "bidpulse.min-increment 必须大于 0")
    private BigDecimal minIncrement = new BigDecimal("0.01");

    @Valid
    private Timeout timeout = new Timeout();
    @Valid
    private Bid bid = new Bid();
    @Valid
    private CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();
    @Valid
    private Fallback fallback = new Fallback();
    @Valid
    private Detail detail = new Detail();
    @Valid
    private Kafka kafka = new Kafka();
    @Valid
    private Budget budget = new Budget();
    @Valid
    private Frequency frequency = new Frequency();
    @Valid
    private Console console = new Console();
    @Valid
    private Auth auth = new Auth();

    /** Spring cron（6 段）；非法值应在启动时失败，而不是等到第一次调度才报错。 */
    static boolean isValidCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            CronExpression.parse(cron);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Data
    public static class Timeout {
        /** 整条链路总超时预算（ms） */
        @Min(value = 50, message = "bidpulse.timeout.total-ms 必须在 50~5000 之间")
        @Max(value = 5000, message = "bidpulse.timeout.total-ms 必须在 50~5000 之间")
        private long totalMs = 1000;
    }

    @Data
    public static class Bid {
        /** 单次竞价的候选上限（SQL LIMIT），同时决定预算预热规模 */
        @Min(value = 1, message = "bidpulse.bid.candidate-limit 必须在 1~500 之间")
        @Max(value = 500, message = "bidpulse.bid.candidate-limit 必须在 1~500 之间")
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
        @Pattern(regexp = "sentinel|local", message = "bidpulse.circuit-breaker.impl 只能是 sentinel 或 local")
        private String impl = "sentinel";
        /** 熔断持续时长（ms）；Sentinel 只接受秒，装载时换算 */
        @Min(value = 1, message = "bidpulse.circuit-breaker.cooldown-ms 必须大于 0")
        private long cooldownMs = 5000;
        /** local 实现：连续失败多少次打开 */
        @Min(value = 1, message = "bidpulse.circuit-breaker.failure-threshold 必须大于 0")
        private int failureThreshold = 5;
        /** Sentinel：慢调用比例阈值 */
        private double slowRatio = 0.5;
        /** Sentinel：统计窗口内最小请求数 */
        @Min(value = 1, message = "bidpulse.circuit-breaker.min-request-amount 必须大于 0")
        private int minRequestAmount = 5;
        /** Sentinel：统计窗口（ms） */
        @Min(value = 100, message = "bidpulse.circuit-breaker.stat-interval-ms 不能小于 100")
        private int statIntervalMs = 1000;
        /** DB 候选检索的慢调用判定阈值（ms） */
        private double dbSlowRtMs = 800;
        /** Redis 预算扣减的慢调用判定阈值（ms） */
        private double redisSlowRtMs = 100;

        @AssertTrue(message = "bidpulse.circuit-breaker.slow-ratio 必须在 (0, 1] 之间")
        public boolean isSlowRatioValid() {
            return slowRatio > 0 && slowRatio <= 1;
        }

        @AssertTrue(message = "bidpulse.circuit-breaker.db-slow-rt-ms 必须大于 0")
        public boolean isDbSlowRtMsValid() {
            return dbSlowRtMs > 0;
        }

        @AssertTrue(message = "bidpulse.circuit-breaker.redis-slow-rt-ms 必须大于 0")
        public boolean isRedisSlowRtMsValid() {
            return redisSlowRtMs > 0;
        }
    }

    @Data
    public static class Fallback {
        /** 兜底总开关；false 仅用于 A/B 对照实验（控制台可运行时切换） */
        private volatile boolean enabled = true;
        /** 强制兜底：复现「依赖全面故障」 */
        private volatile boolean force = false;
        /** 兜底广告本地缓存时长（ms）：正常时最多这么久访问一次 Redis */
        @Min(value = 0, message = "bidpulse.fallback.cache-ttl-ms 不能为负数")
        private long cacheTtlMs = 5000;
        /** Redis 读取失败后的退避时长（ms）：期间直接使用本地缓存 */
        @Min(value = 0, message = "bidpulse.fallback.retry-backoff-ms 不能为负数")
        private long retryBackoffMs = 30000;
    }

    @Data
    public static class Detail {
        /** 测量专用：true 时退化为同步单条 INSERT（模拟 M1 基线） */
        private boolean syncInsert = false;
    }

    @Data
    public static class Kafka {
        @NotBlank(message = "bidpulse.kafka.topic 不能为空")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "bidpulse.kafka.topic 只能包含字母、数字、点、下划线和连字符")
        private String topic = "bid_event";
        /** 死信主题：消费重试耗尽 / 报文不可解析的事件落点（KafkaErrorHandlingConfig） */
        @NotBlank(message = "bidpulse.kafka.dlt-topic 不能为空")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "bidpulse.kafka.dlt-topic 只能包含字母、数字、点、下划线和连字符")
        private String dltTopic = "bid_event.DLT";
        @Min(value = 1, message = "bidpulse.kafka.partitions 必须在 1~100 之间")
        @Max(value = 100, message = "bidpulse.kafka.partitions 必须在 1~100 之间")
        private int partitions = 3;

        // ---- 消费端重试：指数退避，尽量在进入 DLT 前吸收瞬时 DB 抖动 ----
        /** 首次重试间隔（ms） */
        @Min(value = 0, message = "bidpulse.kafka.retry-initial-ms 不能为负数")
        private long retryInitialMs = 1000;
        /** 退避倍数 */
        private double retryMultiplier = 2.0;
        /** 单次重试间隔上限（ms） */
        @Min(value = 0, message = "bidpulse.kafka.retry-max-ms 不能为负数")
        private long retryMaxMs = 16000;
        /** 最大重试次数；默认 1s→2s→4s→8s→16s，总窗口约 31s */
        @Min(value = 0, message = "bidpulse.kafka.retry-max-attempts 不能为负数")
        @Max(value = 100, message = "bidpulse.kafka.retry-max-attempts 不能超过 100")
        private int retryMaxAttempts = 5;

        // ---- DLT 重放：把重试耗尽的事件延迟重新投回主主题 ----
        /** 是否启用 DLT 重放任务 */
        private boolean dltReplayEnabled = true;
        /** 重放任务 cron，默认每 5 分钟 */
        @NotBlank(message = "bidpulse.kafka.dlt-replay-cron 不能为空")
        private String dltReplayCron = "0 */5 * * * ?";
        /** 单次最多处理的消息数，避免一次调度占用过久 */
        @Min(value = 1, message = "bidpulse.kafka.dlt-replay-max-records 必须大于 0")
        private int dltReplayMaxRecords = 1000;
        /** 单次调度最长运行时长（ms） */
        @Min(value = 1, message = "bidpulse.kafka.dlt-replay-max-duration-ms 必须大于 0")
        private long dltReplayMaxDurationMs = 10000;
        /** 同一事件最多重放次数；超过后留在 DLT 等待人工处理 */
        @Min(value = 0, message = "bidpulse.kafka.dlt-replay-max-attempts 不能为负数")
        private int dltReplayMaxAttempts = 3;
        /** 重放投递主主题的同步等待超时（ms）；超时则不提交 DLT 位点，下次重试 */
        @Min(value = 1, message = "bidpulse.kafka.dlt-replay-send-timeout-ms 必须大于 0")
        private long dltReplaySendTimeoutMs = 5000;
        /** processed_event 保留天数：必须不短于 Kafka 消息保留期，否则重放时去重失效 */
        @Min(value = 1, message = "bidpulse.kafka.processed-event-retention-days 必须大于 0")
        private int processedEventRetentionDays = 8;
        /** 单次清理最多删除行数，避免长事务与长时间锁表 */
        @Min(value = 1, message = "bidpulse.kafka.processed-event-cleanup-batch 必须大于 0")
        private int processedEventCleanupBatch = 10000;
        /** 单次调度最多删除的行数上限，避免维护窗口被一次清理占满 */
        @Min(value = 1, message = "bidpulse.kafka.processed-event-cleanup-max-per-run 必须大于 0")
        private int processedEventCleanupMaxPerRun = 1000000;
        /** 去重表清理任务 cron */
        @NotBlank(message = "bidpulse.kafka.processed-event-cleanup-cron 不能为空")
        private String processedEventCleanupCron = "0 30 2 * * ?";

        @AssertTrue(message = "bidpulse.kafka.retry-multiplier 必须大于等于 1")
        public boolean isRetryMultiplierValid() {
            return retryMultiplier >= 1;
        }

        @AssertTrue(message = "bidpulse.kafka.dlt-replay-cron 不是合法的 Spring cron")
        public boolean isDltReplayCronValid() {
            return isValidCron(dltReplayCron);
        }

        @AssertTrue(message = "bidpulse.kafka.processed-event-cleanup-cron 不是合法的 Spring cron")
        public boolean isProcessedEventCleanupCronValid() {
            return isValidCron(processedEventCleanupCron);
        }
    }

    @Data
    public static class Budget {
        /** 播种 key 的 TTL（秒） */
        @Min(value = 1, message = "bidpulse.budget.key-ttl-seconds 必须大于 0")
        private long keyTtlSeconds = 172800;
        @Valid
        private Cleanup cleanup = new Cleanup();
        @Valid
        private Reset reset = new Reset();
        @Valid
        private Recon recon = new Recon();

        @Data
        public static class Cleanup {
            /** 保留「今天」往前共 N 天的日期分桶 */
            @Min(value = 1, message = "bidpulse.budget.cleanup.retention-days 必须大于 0")
            private int retentionDays = 2;
            @NotBlank(message = "bidpulse.budget.cleanup.cron 不能为空")
            private String cron = "0 0 1 * * ?";

            @AssertTrue(message = "bidpulse.budget.cleanup.cron 不是合法的 Spring cron")
            public boolean isCronValid() {
                return isValidCron(cron);
            }
        }

        @Data
        public static class Reset {
            /** 日预算复位（status 3 -> 1）的触发时间 */
            @NotBlank(message = "bidpulse.budget.reset.cron 不能为空")
            private String cron = "0 5 0 * * ?";

            @AssertTrue(message = "bidpulse.budget.reset.cron 不是合法的 Spring cron")
            public boolean isCronValid() {
                return isValidCron(cron);
            }
        }

        @Data
        public static class Recon {
            /** 是否启用预算对账任务 */
            private boolean enabled = true;
            /** 对账 cron，默认每小时第 15 分钟 */
            @NotBlank(message = "bidpulse.budget.recon.cron 不能为空")
            private String cron = "0 15 * * * ?";
            /** 允许的误差（分）：Redis 与 DB 账单差值不超过该值视为正常（事件在途） */
            @Min(value = 0, message = "bidpulse.budget.recon.tolerance-cents 不能为负数")
            private long toleranceCents = 1;
            /** 单批处理的广告数，控制 IN 查询与 Redis 读取规模 */
            @Min(value = 1, message = "bidpulse.budget.recon.batch-size 必须大于 0")
            private int batchSize = 500;

            @AssertTrue(message = "bidpulse.budget.recon.cron 不是合法的 Spring cron")
            public boolean isCronValid() {
                return isValidCron(cron);
            }
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
        private volatile boolean enabled = true;
        /** 同一用户对同一广告每小时的最大成交次数 */
        @Min(value = 1, message = "bidpulse.frequency.max-per-hour 必须大于 0")
        private volatile int maxPerHour = 3;
        /**
         * 频次 key 的 TTL（秒），默认 2 小时。
         *
         * <p>固定窗口键按小时命名，旧窗口不会再被读取；TTL 只负责回收。
         * 取 2 小时给跨小时边界留出冗余，避免窗口末尾写入的键过早消失。
         */
        @Min(value = 1, message = "bidpulse.frequency.key-ttl-seconds 必须大于 0")
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
        private volatile boolean allowWrite = false;
        @Valid
        private ReadDb readDb = new ReadDb();

        /**
         * 控制台只读连接池。容量刻意远小于主池：控制台是旁路，
         * 不允许它把竞价/消费的连接占满。
         */
        @Data
        public static class ReadDb {
            /** 只读池最大连接数 */
            @Min(value = 1, message = "bidpulse.console.read-db.max-pool-size 必须在 1~20 之间")
            @Max(value = 20, message = "bidpulse.console.read-db.max-pool-size 必须在 1~20 之间")
            private int maxPoolSize = 4;
            /** 常驻空闲连接数；必须不大于最大连接数 */
            @Min(value = 0, message = "bidpulse.console.read-db.min-idle 必须在 0~20 之间")
            @Max(value = 20, message = "bidpulse.console.read-db.min-idle 必须在 0~20 之间")
            private int minIdle = 1;
            /** 取连接超时（ms）；控制台宁可快速失败，也不长时间占住 Tomcat 线程 */
            @Min(value = 500, message = "bidpulse.console.read-db.connection-timeout-ms 必须在 500~10000 之间")
            @Max(value = 10000, message = "bidpulse.console.read-db.connection-timeout-ms 必须在 500~10000 之间")
            private long connectionTimeoutMs = 3000;
            /** 单条查询超时（秒） */
            @Min(value = 1, message = "bidpulse.console.read-db.query-timeout-seconds 必须在 1~30 之间")
            @Max(value = 30, message = "bidpulse.console.read-db.query-timeout-seconds 必须在 1~30 之间")
            private int queryTimeoutSeconds = 2;

            @AssertTrue(message = "bidpulse.console.read-db.min-idle 不能大于 max-pool-size")
            public boolean isMinIdleValid() {
                return minIdle <= maxPoolSize;
            }
        }
    }

    /**
     * 控制台与管理端认证配置。
     *
     * <p>密码只从环境变量注入，不写入仓库；未配置时启动会生成一次性随机密码并在日志中提示，
     * 仅用于本地演示。生产必须显式设置 {@code BIDPULSE_AUTH_SECRET} 与各账号密码。
     */
    @Data
    public static class Auth {
        /** Bearer token 有效期（秒），默认 8 小时 */
        @Min(value = 60, message = "bidpulse.auth.token-ttl-seconds 不能小于 60")
        private long tokenTtlSeconds = 28800;
        /** HMAC-SHA256 签名密钥；为空时启动生成随机密钥，重启后旧 token 失效 */
        private String secret = "";
        /** 用户名 -> 账号配置 */
        @Valid
        private Map<String, AuthUser> users = new LinkedHashMap<>();
    }

    @Data
    public static class AuthUser {
        /** 明文密码只从环境变量/测试配置注入，启动后立即编码，不持久化 */
        private String password = "";
        /** VIEWER / OPERATOR / ADMIN */
        @Pattern(regexp = "VIEWER|OPERATOR|ADMIN", message = "bidpulse.auth.users.*.role 只能是 VIEWER/OPERATOR/ADMIN")
        private String role = "VIEWER";
    }
}
