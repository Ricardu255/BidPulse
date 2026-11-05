package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.console.dto.DataView;
import com.bidpulse.console.query.ConsoleDataQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据浏览 controller 的契约测试：只接收参数、委托 query 层、包装响应。
 *
 * <p>白名单与 SQL 细节在 {@link com.bidpulse.console.query.ConsoleDataQueryServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
class ConsoleDataControllerTest {

    @Mock
    private ConsoleDataQueryService dataQueryService;

    private ConsoleDataController controller;

    @BeforeEach
    void setUp() {
        controller = new ConsoleDataController(dataQueryService);
    }

    @Test
    @DisplayName("参数原样委托给查询服务，响应包一层 ApiResponse")
    void delegatesToQueryService() {
        DataView view = new DataView("bid_log", List.of("ad_id"), List.of(Map.of("ad_id", 110)));
        when(dataQueryService.query("bid_log", 3, 110L, "req-1")).thenReturn(view);

        ApiResponse<DataView> response = controller.query("bid_log", 3, 110L, "req-1");

        assertSame(view, response.getData());
        verify(dataQueryService).query("bid_log", 3, 110L, "req-1");
    }

    @Test
    @DisplayName("可选过滤参数为空时同样透传 null")
    void passesNullFiltersThrough() {
        when(dataQueryService.query("processed_event", 50, null, null))
                .thenReturn(new DataView("processed_event", List.of(), List.of()));

        controller.query("processed_event", 50, null, null);

        verify(dataQueryService).query("processed_event", 50, null, null);
    }

    @Test
    @DisplayName("controller 不吞异常：查询服务的 400 直接抛给全局异常处理")
    void propagatesServiceFailure() {
        when(dataQueryService.query("mysql_user", 20, null, null))
                .thenThrow(BizException.badRequest("不支持的数据表: mysql_user"));

        assertThrows(BizException.class, () -> controller.query("mysql_user", 20, null, null));
        verify(dataQueryService).query("mysql_user", 20, null, null);
    }
}
