package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.console.dto.DataView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据浏览的白名单与参数化查询测试（不连数据库）。
 */
@ExtendWith(MockitoExtension.class)
class ConsoleDataQueryServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private ConsoleDataQueryService service;

    @BeforeEach
    void setUp() {
        service = new ConsoleDataQueryService(jdbc);
    }

    @Test
    @DisplayName("不支持的表名返回 400，不执行 SQL")
    void unknownTableReturns400() {
        BizException e = assertThrows(BizException.class,
                () -> service.query("mysql_user", 20, null, null));
        assertEquals(400, e.getStatus());
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("bid_log 过滤值全部走占位符，LIMIT 被钳制并作为参数")
    void bidLogUsesParameterizedQuery() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("ad_id", 110)));

        DataView view = service.query("bid_log", 3, 110L, "req-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());

        assertTrue(sql.getValue().contains("request_id = ?"));
        assertTrue(sql.getValue().contains("ad_id = ?"));
        assertTrue(sql.getValue().endsWith("ORDER BY id DESC LIMIT ?"));
        assertArrayEquals(new Object[]{110L, "req-1", 3}, args.getValue());
        assertEquals("bid_log", view.table());
        assertEquals(1, view.rows().size());
    }

    @Test
    @DisplayName("limit 超过 200 时按 200 截断")
    void limitIsClamped() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.query("billing_event", 9999, null, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        assertEquals(200, args.getValue()[args.getValue().length - 1]);
    }

    @Test
    @DisplayName("limit 小于 1 时按 1 截断，避免 LIMIT 0 / 负数")
    void limitHasLowerBound() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.query("ad_freq", -5, null, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        assertEquals(1, args.getValue()[args.getValue().length - 1]);
    }

    @ParameterizedTest(name = "{0} 可查询")
    @ValueSource(strings = {"bid_log", "billing_event", "bid_stat_hourly", "ad_freq", "processed_event"})
    @DisplayName("白名单内的五张表都能查询")
    void whitelistedTablesAreQueryable(String table) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        DataView view = service.query(table, 10, null, null);

        assertEquals(table, view.table());
        assertFalse(view.columns().isEmpty());
    }

    @Test
    @DisplayName("requestId 只有空白字符时视为未传，不拼过滤条件")
    void blankRequestIdIsIgnored() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.query("bid_log", 10, null, "   ");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertFalse(sql.getValue().contains("request_id = ?"));
        assertArrayEquals(new Object[]{10}, args.getValue());
    }

    @Test
    @DisplayName("processed_event 不支持 adId 过滤，参数只有 LIMIT")
    void processedEventOnlyTakesLimit() {
        when(jdbc.queryForList(anyString(), any(Integer.class))).thenReturn(List.of());

        service.query("processed_event", 25, 110L, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(25));
        assertFalse(sql.getValue().contains("ad_id = ?"));
        assertTrue(sql.getValue().contains("LIMIT ?"));
    }
}
