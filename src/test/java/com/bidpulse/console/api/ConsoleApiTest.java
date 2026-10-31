package com.bidpulse.console.api;

import com.bidpulse.admin.AdAdminService;
import com.bidpulse.api.PageResult;
import com.bidpulse.api.dto.AdQuery;
import com.bidpulse.api.dto.AdUpsertRequest;
import com.bidpulse.api.dto.BidRequest;
import com.bidpulse.bid.BidService;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.dto.BudgetView;
import com.bidpulse.console.dto.DltMessageView;
import com.bidpulse.console.dto.FrequencyView;
import com.bidpulse.console.dto.OverviewView;
import com.bidpulse.console.dto.TrendPoint;
import com.bidpulse.console.query.ConsoleOverviewService;
import com.bidpulse.console.query.ConsoleQueryService;
import com.bidpulse.console.query.DltQueryService;
import com.bidpulse.console.query.DltReplayAdminService;
import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import com.bidpulse.job.BudgetReconJob;
import com.bidpulse.job.DltReplayJob;
import com.bidpulse.job.ReconReportStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制台 REST 契约测试：只起 MVC 切片，不连 Redis/MySQL/Kafka。
 *
 * <p>锁住前端依赖的字段名与统一响应结构，避免后续改 DTO 时静默破坏页面。
 */
@WebMvcTest(controllers = {
        ConsoleBidController.class,
        ConsoleBudgetController.class,
        ConsoleOverviewController.class,
        ConsoleAdsController.class,
        ConsoleOpsController.class,
        ConsoleDataController.class
})
@TestPropertySource(properties = {
        "bidpulse.console.enabled=true",
        "bidpulse.console.allowed-origins=http://localhost:5173"
})
class ConsoleApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BidService bidService;

    @MockBean
    private ConsoleQueryService queryService;

    @MockBean
    private ConsoleOverviewService overviewService;

    @MockBean
    private AdAdminService adAdminService;

    @MockBean
    private AdMapper adMapper;

    @MockBean
    private BidPulseProperties props;

    @MockBean
    private DltQueryService dltQueryService;

    @MockBean
    private DltReplayAdminService dltReplayAdminService;

    @MockBean
    private DltReplayJob dltReplayJob;

    @MockBean
    private BudgetReconJob budgetReconJob;

    @MockBean
    private ReconReportStore reconReportStore;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void enableConsoleWrite() {
        BidPulseProperties.Console console = new BidPulseProperties.Console();
        console.setAllowWrite(true);
        when(props.getConsole()).thenReturn(console);
    }

    @Test
    @DisplayName("竞价接口返回 data + wallMs，字段与前端类型一致")
    void bidReturnsResultAndWallMs() throws Exception {
        BidRequest.Result result = new BidRequest.Result();
        result.setRequestId("req-1");
        result.setHasAd(true);
        result.setFallback(false);
        result.setPriceMode(1);
        result.setPayPrice(new BigDecimal("1.01"));
        result.setCandidateCnt(3);
        result.setMatchCnt(3);
        when(bidService.bid(any(BidRequest.class))).thenReturn(result);

        mockMvc.perform(post("/console-api/v1/bids")
                        .contentType("application/json")
                        .content("{\"slotCode\":\"slot-01\",\"region\":\"110000\",\"age\":28,\"gender\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value("req-1"))
                .andExpect(jsonPath("$.data.priceMode").value(1))
                .andExpect(jsonPath("$.data.payPrice").value(1.01))
                .andExpect(jsonPath("$.wallMs").exists());
    }

    @Test
    @DisplayName("预算接口返回分单位余额与广告基础信息")
    void budgetReturnsView() throws Exception {
        when(queryService.budget(110L)).thenReturn(new BudgetView(
                110L, "创意标题-110", 1, new BigDecimal("1.5000"), new BigDecimal("1000.00"),
                9900L, 100L, 172800L, true));

        mockMvc.perform(get("/console-api/v1/budget").param("adId", "110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.adId").value(110))
                .andExpect(jsonPath("$.data.remainingCents").value(9900))
                .andExpect(jsonPath("$.data.seeded").value(true));
    }

    @Test
    @DisplayName("匿名频次返回 enabled=false，不伪造 0")
    void anonymousFrequencyIsDisabled() throws Exception {
        when(queryService.frequency(null, 110L))
                .thenReturn(new FrequencyView(null, 110L, false, null, 3, null));

        mockMvc.perform(get("/console-api/v1/frequency").param("adId", "110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.count").doesNotExist());
    }

    @Test
    @DisplayName("概览接口返回 KPI、健康与趋势字段")
    void overviewReturnsAggregates() throws Exception {
        when(overviewService.overview()).thenReturn(new OverviewView(
                100, 2, 0.02, new BigDecimal("12.34"), 48,
                200, 190, 7, 3, 0,
                Map.of("overall", "UP", "db", "UP"),
                List.of(new TrendPoint("10:00", 100, 2))));

        mockMvc.perform(get("/console-api/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bidsToday").value(100))
                .andExpect(jsonPath("$.data.fallbackRate").value(0.02))
                .andExpect(jsonPath("$.data.health.db").value("UP"))
                .andExpect(jsonPath("$.data.trend[0].minute").value("10:00"));
    }

    @Test
    @DisplayName("广告列表复用 AdAdminService 的分页结果")
    void adsReturnsPage() throws Exception {
        Ad ad = new Ad();
        ad.setId(110L);
        ad.setAdvertiserId(11);
        ad.setTitle("创意标题-110");
        ad.setCategoryCode("travel");
        ad.setBid(new BigDecimal("1.5000"));
        ad.setDailyBudget(new BigDecimal("1000.00"));
        ad.setStatus(1);
        when(adAdminService.page(any(AdQuery.class)))
                .thenReturn(PageResult.of(1, 1, 10, List.of(ad)));

        mockMvc.perform(get("/console-api/v1/ads").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(110))
                .andExpect(jsonPath("$.data.list[0].title").value("创意标题-110"));
    }

    @Test
    @DisplayName("DLT 浏览返回消息与重放次数")
    void dltMessagesReturnView() throws Exception {
        when(dltQueryService.messages(20)).thenReturn(List.of(new DltMessageView(
                "bid_event.DLT", 0, 42, "110", "{\"eventId\":\"e-1\"}",
                2, false, "2026-10-31 10:20:00", Map.of("x-replay-count", "2"))));

        mockMvc.perform(get("/console-api/v1/dlt/messages").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].partition").value(0))
                .andExpect(jsonPath("$.data[0].offset").value(42))
                .andExpect(jsonPath("$.data[0].replayCount").value(2));
    }

    @Test
    @DisplayName("对账状态返回最近一次结果")
    void reconReturnsLastReport() throws Exception {
        when(reconReportStore.last()).thenReturn(new ReconReportStore.ReconReport(
                "2026-10-31 10:30:00", "OK", 128, 1, 2, 340, null));

        mockMvc.perform(get("/console-api/v1/recon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.adCount").value(128))
                .andExpect(jsonPath("$.data.repaired").value(2));
    }

    @Test
    @DisplayName("新建广告复用 AdAdminService 并返回 id")
    void createAdReturnsId() throws Exception {
        when(adAdminService.create(any(AdUpsertRequest.class))).thenReturn(123L);

        mockMvc.perform(post("/console-api/v1/ads")
                        .contentType("application/json")
                        .content("{\"advertiserId\":1,\"title\":\"新广告\","
                                + "\"creativeUrl\":\"https://cdn.example.com/a.jpg\","
                                + "\"categoryCode\":\"travel\",\"bid\":1.2,\"dailyBudget\":1000,"
                                + "\"targets\":[{\"dimType\":1,\"dimValue\":\"110000\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(123));
    }
}
