package com.bidpulse.api;

import com.bidpulse.api.controller.BidController;
import com.bidpulse.bid.BidService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 边界错误的单测 —— 只起 MVC 切片，不连 MySQL / Redis / Kafka。
 *
 * <p>锁住 {@code GlobalExceptionHandler} 最容易漏掉的两种「框架异常」：
 * 它们发生在进入 controller 之前 / 找不到 controller 时，若不显式声明，
 * 会被 {@code @ExceptionHandler(Exception.class)} 吞成 500。
 */
@WebMvcTest(BidController.class)
class ApiErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BidService bidService;

    @Test
    @DisplayName("Content-Type 不支持 → 415，响应体仍是统一结构")
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/bid").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    @DisplayName("未知路径 → 404，而不是被 Exception 兜底成 500")
    void unknownPath_returns404() throws Exception {
        mockMvc.perform(get("/not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
