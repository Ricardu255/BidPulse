package com.bidpulse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.bidpulse.config.BidPulseProperties;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 竞价主链路端到端集成测试（A1：真连 Kafka）。
 *
 * <p><b>不 mock 任何 Kafka bean、不关闭监听器</b>：MySQL / Redis / Kafka 三者都需真实可用
 * ——本地由 docker-compose（bidpulse-mysql / bidpulse-redis / bidpulse-kafka）提供，CI 由 services 提供。
 *
 * <p>断言覆盖两层：
 * <ul>
 *   <li>同步链路：{@code /bid} 返回胜出者 900002、二价 {@code priceMode=1}；
 *       余额不足的 900001 在扣减失败后回写 {@code status=3}；</li>
 *   <li>异步旁路：一条 {@code bid_event} 被三个消费组真实消费，分别落
 *       {@code billing_event} / {@code bid_stat_hourly} / {@code ad_freq}。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class BidFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private BidPulseProperties props;

    @Value("${bidpulse.kafka.topic:bid_event}")
    private String topic;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** 频次用例的固定用户前缀，清理时按前缀扫 */
    private static final String FREQ_USER = "flow-freq-user-a";
    private static final String FREQ_USER_B = "flow-freq-user-b";

    /** 消费组异步落库的等待上限（ms）：Kafka 消费 + 消费组 rebalance 需留足时间 */
    private static final long AWAIT_TIMEOUT_MS = 15_000;

    /**
     * 等生产者拿到 topic 元数据，再发竞价请求。
     *
     * <p><b>为什么需要</b>：Kafka 生产者是懒加载的，首次 {@code send()} 才去拉元数据；
     * 而本项目为了不拖住 {@code /bid} 把 {@code max.block.ms} 压到 100ms。
     * 元数据没就绪时这次发送会直接抛 {@code Topic ... not present in metadata after 100 ms}，
     * 事件是 fire-and-forget，失败只记日志——用例随后等消费落库必然超时。
     *
     * <p>这里只查元数据（不投递消息，也不会污染 DLT），把用例从「依赖启动顺序/机器快慢」
     * 变成「依赖 Kafka 可用」。
     */
    private void awaitProducerMetadata() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!kafkaTemplate.partitionsFor(topic).isEmpty()) {
                    return;
                }
            } catch (Exception ignored) {
                // 元数据未就绪：max.block.ms=100 会很快超时，重试即可
            }
            Thread.sleep(200);
        }
        fail("Kafka 元数据在 30s 内未就绪: " + topic);
    }

    @AfterEach
    void cleanRedisBudgetKeys() {
        String d = LocalDate.now().format(FMT);
        redis.delete(List.of(
                "budget:900001:" + d, "spent:900001:" + d,
                "budget:900002:" + d, "spent:900002:" + d,
                "budget:900003:" + d, "spent:900003:" + d));
        var freqKeys = redis.keys("freq:u:flow-freq-*");
        if (freqKeys != null && !freqKeys.isEmpty()) {
            redis.delete(freqKeys);
        }
    }

    /**
     * 命中一条高出价但预算极低的广告（900001）：它排第一被尝试扣减，余额不足被跳过，
     * 并触发回写 MySQL status=3；最终胜出者是有充足预算的 900002。
     *
     * <p>同时校验二价的「有效出价」语义：出价 0.10 的 900003 当天额度只有 0.01 元，
     * 有效出价 = min(0.10, 0.01) = 0.01，成交价 = min(0.50, 0.01 + 0.01) = 0.02。
     * 旧实现会直接取次高出价算出 0.11，或在跳过耗尽候选后回落到自身出价 0.50。
     *
     * <p>随后校验该竞价事件已被三个消费组各自消费落库（真 Kafka 旁路）。
     */
    @Test
    void bid_endToEnd_winnerBilledAndConsumedByAllGroups() throws Exception {
        // 先确保生产者已拿到 topic 元数据，否则 max.block.ms=100 会让这次事件发送直接失败
        awaitProducerMetadata();

        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\",\"ageBucket\":\"25-30\",\"age\":28,"
                                + "\"gender\":0,\"slotCode\":\"test-slot\",\"categoryCode\":\"industrial\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasAd").value(true))
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.winner.adId").value(900002))
                .andExpect(jsonPath("$.data.priceMode").value(1))
                .andExpect(jsonPath("$.data.payPrice").value(0.02))
                .andExpect(jsonPath("$.data.candidateCnt").value(3));

        // 同步链路：余额不足的高出价广告（900001）由 ExhaustedBuffer 在后台回写 status=3。
        // 回写是异步的（攒批 50ms），这里轮询等待，避免把「异步」当成「同步」导致偶发失败。
        awaitStatus(900001L, 3);

        // 异步旁路：三个消费组各自消费同一条 bid_event（真投真消费）
        awaitRowsByAdId("billing_event", "billing-recon");
        awaitRowsByAdId("bid_stat_hourly", "hourly-preagg");
        awaitRowsByAdId("ad_freq", "freq-antifraud");
    }

    /**
     * P1 实时频次控制端到端：同一用户当前小时对 900002 的成交次数已达上限时，
     * 该候选被跳过（不扣预算、不标记 status=3），整单因无其它可支付候选而走兜底；
     * 换一个 userId 后 900002 重新可投。
     *
     * <p>频次计数直接预置到当前小时键，避免用例跨越整点导致窗口切换的偶发失败；
     * 「成功才计数、按用户/广告隔离、跨小时重置」由 {@code BudgetServiceTest} 用固定时间覆盖。
     */
    @Test
    void bid_frequencyCapSkipsCappedAd_andDoesNotMarkItExhausted() throws Exception {
        awaitProducerMetadata();

        String freqKey = "freq:u:" + FREQ_USER + ":" + LocalDateTime.now().format(HOUR_FMT);
        redis.opsForHash().put(freqKey, "900002",
                String.valueOf(Math.max(1, props.getFrequency().getMaxPerHour())));
        redis.expire(freqKey, Duration.ofSeconds(props.getFrequency().getKeyTtlSeconds()));

        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\",\"ageBucket\":\"25-30\",\"age\":28,"
                                + "\"gender\":0,\"slotCode\":\"test-slot\","
                                + "\"categoryCode\":\"industrial\","
                                + "\"userId\":\"" + FREQ_USER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasAd").value(true))
                .andExpect(jsonPath("$.data.fallback").value(true))
                .andExpect(jsonPath("$.data.winner.adId").value(999999))
                .andExpect(jsonPath("$.data.priceMode").value(2));

        // 900003 因余额不足被后台回写 status=3；同一轮批处理绝不能把频次超限的 900002 也写成 3。
        awaitStatus(900003L, 3);
        Integer winnerStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ad WHERE id = 900002", Integer.class);
        assertEquals(1, winnerStatus, "频次超限是正常业务结果，不能把广告标成已耗尽");

        // 另一个用户不受影响
        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\",\"ageBucket\":\"25-30\",\"age\":28,"
                                + "\"gender\":0,\"slotCode\":\"test-slot\","
                                + "\"categoryCode\":\"industrial\","
                                + "\"userId\":\"" + FREQ_USER_B + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.winner.adId").value(900002));
    }

    /**
     * 入参校验 + 全局异常处理：缺少必填的 slotCode 时，请求在进 Service 之前就被拦下，
     * 由 {@code GlobalExceptionHandler} 返回 400 且响应体仍是统一结构（code=400、msg 含字段名）。
     */
    @Test
    void bid_missingSlotCode_returns400WithUnifiedBody() throws Exception {
        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\",\"age\":28}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("slotCode")));
    }

    /**
     * 入参越界：age 超出 0~120 时同样被 {@code @Min/@Max} 拦下返回 400。
     */
    @Test
    void bid_ageOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\",\"slotCode\":\"test-slot\",\"age\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("age")));
    }

    /**
     * 请求体不可读（非法 JSON）：由 {@code HttpMessageNotReadableException} 分支兜住，返回 400 而非 500。
     */
    @Test
    void bid_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/bid")
                        .contentType("application/json")
                        .content("{\"region\":\"440318\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 轮询等待广告状态被后台缓冲回写；超时则失败并打印当前状态。 */
    private void awaitStatus(long adId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        Integer last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbcTemplate.queryForObject(
                    "SELECT status FROM ad WHERE id = ?", Integer.class, adId);
            if (last != null && last == expected) {
                return;
            }
            Thread.sleep(50);
        }
        fail("广告 " + adId + " 未在 5s 内变为 status=" + expected + "，当前=" + last);
    }

    /** 轮询等待指定消费组把本轮事件（ad_id=900002）落库；超时则失败并指出是哪个组。 */
    private void awaitRowsByAdId(String table, String group) throws InterruptedException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE ad_id = 900002";
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        int last = 0;
        while (System.currentTimeMillis() < deadline) {
            Integer c = jdbcTemplate.queryForObject(sql, Integer.class);
            last = c == null ? 0 : c;
            if (last >= 1) {
                return;
            }
            Thread.sleep(200);
        }
        fail("消费组 [" + group + "] 未在 " + AWAIT_TIMEOUT_MS + "ms 内落库: " + table + " 实际行数=" + last);
    }
}
