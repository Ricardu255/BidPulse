package com.bidpulse.console.api;

import com.bidpulse.api.BizException;
import com.bidpulse.console.dto.DataView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据浏览的白名单与参数化查询测试（不连数据库）。
 */
@ExtendWith(MockitoExtension.class)
class ConsoleDataControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    private ConsoleDataController controller;

    @BeforeEach
    void setUp() {
        controller = new ConsoleDataController(jdbc);
    }

    @Test
    @DisplayName("不支持的表名返回 400，不执行 SQL")
    void unknownTableReturns400() {
        BizException e = assertThrows(BizException.class,
                () -> controller.query("mysql_user", 20, null, null));
        assertEquals(400, e.getStatus());
    }

    @Test
    @DisplayName("bid_log 过滤值全部走占位符，LIMIT 被钳制并作为参数")
    void bidLogUsesParameterizedQuery() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("ad_id", 110)));

        DataView view = controller.query("bid_log", 3, 110L, "req-1").getData();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());

        assertTrue(sql.getValue().contains("request_id = ?"));
        assertTrue(sql.getValue().contains("ad_id = ?"));
        assertArrayEquals(new Object[]{110L, "req-1", 3}, args.getValue());
        assertEquals("bid_log", view.table());
        assertEquals(1, view.rows().size());
    }

    @Test
    @DisplayName("limit 超过 200 时按 200 截断")
    void limitIsClamped() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        controller.query("billing_event", 9999, null, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        assertEquals(200, args.getValue()[args.getValue().length - 1]);
    }
}
