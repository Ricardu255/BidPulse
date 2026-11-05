package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.api.dto.BidTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 轨迹查询的边界测试：参数校验、记录缺失、轨迹未落库、脏 JSON。
 *
 * <p>控制台刚发起竞价时明细可能还没刷盘，接口会短暂 404；前端按「稍后重试」处理，
 * 这里锁住这个语义，避免把「还没写入」误报成 500。
 */
class ConsoleBidTraceServiceTest {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";

    private static final String TRACE_JSON = """
            {
              "requestId": "0123456789abcdef0123456789abcdef",
              "ts": 1730000000000,
              "outcome": "WIN",
              "fallbackReason": null,
              "candidateCnt": 3,
              "matchCnt": 3,
              "winnerAdId": 110,
              "winBid": 1.50,
              "payPrice": 1.01,
              "priceMode": 1,
              "candidates": [
                {"adId": 110, "bid": 1.50, "price": 1.01, "result": "SUCCESS"}
              ],
              "steps": [
                {"stage": "检索", "detail": "命中候选 3 条", "costMs": 8}
              ],
              "timings": {"dbMs": 8, "redisMs": 12, "totalMs": 21}
            }
            """;

    private JdbcTemplate jdbc;
    private ConsoleBidTraceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ConsoleBidTraceService(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("合法 requestId 返回解析后的轨迹")
    void returnsParsedTrace() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any()))
                .thenReturn(TRACE_JSON);

        BidTrace trace = service.trace(REQUEST_ID);

        assertEquals(REQUEST_ID, trace.requestId());
        assertEquals("WIN", trace.outcome());
        assertEquals(new BigDecimal("1.01"), trace.payPrice());
        assertEquals("SUCCESS", trace.candidates().get(0).result());
        assertEquals(21, trace.timings().totalMs());
    }

    @Test
    @DisplayName("非法 requestId 直接 400，不查数据库")
    void rejectsMalformedRequestId() {
        BizException e = assertThrows(BizException.class, () -> service.trace("not-a-request-id"));

        assertEquals(400, e.getStatus());
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("记录不存在返回 404")
    void missingRecordIsNotFound() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any()))
                .thenThrow(new EmptyResultDataAccessException(1));

        BizException e = assertThrows(BizException.class, () -> service.trace(REQUEST_ID));
        assertEquals(404, e.getStatus());
    }

    @Test
    @DisplayName("记录存在但没有轨迹（外部 /bid）返回 404")
    void nullTraceIsNotFound() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.trace(REQUEST_ID));
        assertEquals(404, e.getStatus());
    }

    @Test
    @DisplayName("轨迹 JSON 损坏返回 503，不把脏数据透给前端")
    void malformedJsonIsUnavailable() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn("{not-json");

        BizException e = assertThrows(BizException.class, () -> service.trace(REQUEST_ID));
        assertEquals(503, e.getStatus());
    }
}
