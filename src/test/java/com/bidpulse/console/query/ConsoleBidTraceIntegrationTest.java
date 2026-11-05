package com.bidpulse.console.query;

import com.bidpulse.api.dto.BidRequest;
import com.bidpulse.api.dto.BidTrace;
import com.bidpulse.bid.BidService;
import com.bidpulse.testsupport.BidPulseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 决策轨迹端到端：控制台竞价 → 异步落 {@code bid_log.trace_json} → 轨迹查询接口。
 *
 * <p>锁住三件事：
 * <ul>
 *   <li>控制台竞价（{@code bid(req, true)}）同步返回轨迹，且轨迹里的胜出候选标记 SUCCESS；</li>
 *   <li>轨迹随明细异步落库，稍后可通过 {@link ConsoleBidTraceService} 回看；</li>
 *   <li>外部 {@code /bid} 路径（{@code bid(req)}）不采集轨迹——热路径零开销的边界不被破坏。</li>
 * </ul>
 *
 * <p>明细由 {@code BidDetailBuffer} 每 200ms 刷盘，用例统一轮询等待，不把异步当同步。
 */
@SpringBootTest
@BidPulseIntegrationTest
@Sql(scripts = "classpath:test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "classpath:test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ConsoleBidTraceIntegrationTest {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SLOT = "it-trace-slot";
    private static final long AWAIT_TIMEOUT_MS = 5_000;

    @Autowired
    private BidService bidService;
    @Autowired
    private ConsoleBidTraceService traceService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    /** 本用例产生的 requestId，结束后删掉对应 bid_log 行，避免测试库累积 */
    private final List<String> requestIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!requestIds.isEmpty()) {
            jdbc.update("DELETE FROM bid_log WHERE request_id IN ("
                    + String.join(",", requestIds.stream().map(id -> "?").toList()) + ")",
                    requestIds.toArray());
        }
        String day = LocalDate.now().format(DAY_FMT);
        redis.delete(List.of(
                "budget:900001:" + day, "spent:900001:" + day,
                "budget:900002:" + day, "spent:900002:" + day,
                "budget:900003:" + day, "spent:900003:" + day));
    }

    @Test
    @DisplayName("控制台竞价返回轨迹，并异步落库后可回看")
    void consoleBidTraceIsPersistedAndReadable() throws Exception {
        BidRequest.Result result = bidService.bid(consoleRequest(), true);
        requestIds.add(result.getRequestId());

        BidTrace trace = result.getTrace();
        assertNotNull(trace, "控制台竞价必须同步返回轨迹");
        assertEquals("WIN", trace.outcome());
        assertNotNull(trace.winnerAdId());
        assertTrue(trace.candidateCnt() >= 1);
        assertFalse(trace.candidates().isEmpty());
        assertTrue(trace.candidates().stream().anyMatch(c -> "SUCCESS".equals(c.result())),
                "胜出候选必须出现在候选裁决里");

        String json = awaitTraceJson(result.getRequestId());
        assertNotNull(json, "控制台竞价的 trace_json 必须落库");
        // MySQL JSON 列会规范化空白（`"outcome": "WIN"`），不要用无空格的字符串匹配
        JsonNode persistedJson = new ObjectMapper().readTree(json);
        assertEquals("WIN", persistedJson.path("outcome").asText(), "落库内容应与返回轨迹一致");

        BidTrace persisted = traceService.trace(result.getRequestId());
        assertEquals(result.getRequestId(), persisted.requestId());
        assertEquals(trace.winnerAdId(), persisted.winnerAdId());
        assertEquals(trace.payPrice(), persisted.payPrice());
        assertFalse(persisted.steps().isEmpty(), "时间线步骤不能为空");
    }

    @Test
    @DisplayName("外部竞价路径不采集轨迹，trace_json 保持 NULL")
    void externalBidDoesNotCaptureTrace() throws Exception {
        BidRequest.Result result = bidService.bid(consoleRequest());
        requestIds.add(result.getRequestId());

        assertNull(result.getTrace(), "外部 /bid 不应生成轨迹");

        String json = awaitTraceJson(result.getRequestId());
        assertNull(json, "外部 /bid 的 trace_json 应为 NULL");
    }

    private static BidRequest consoleRequest() {
        BidRequest req = new BidRequest();
        req.setSlotCode(SLOT);
        req.setRegion("440318");
        req.setAge(28);
        req.setGender(0);
        req.setCategoryCode("industrial");
        return req;
    }

    /**
     * 等待明细刷盘，返回 trace_json（可能为 NULL）。
     *
     * <p>用 {@code queryForList} 而不是 {@code queryForObject}：既要区分「行还没刷出来」
     * 与「行已存在但 trace_json 为 NULL」，又不能在后者抛 EmptyResult。
     */
    private String awaitTraceJson(String requestId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<String> rows = jdbc.queryForList(
                    "SELECT trace_json FROM bid_log WHERE request_id = ?", String.class, requestId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        fail("bid_log 未在 " + AWAIT_TIMEOUT_MS + "ms 内刷出 requestId=" + requestId);
        return null;
    }
}
