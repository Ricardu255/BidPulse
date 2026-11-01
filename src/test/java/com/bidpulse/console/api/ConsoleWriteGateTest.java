package com.bidpulse.console.api;

import com.bidpulse.admin.AdAdminService;
import com.bidpulse.api.BizException;
import com.bidpulse.api.dto.AdUpsertRequest;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.command.DltReplayAdminService;
import com.bidpulse.console.query.DltQueryService;
import com.bidpulse.infra.kafka.KafkaLagQueryService;
import com.bidpulse.job.BudgetReconJob;
import com.bidpulse.job.DltReplayJob;
import com.bidpulse.job.ReconReportStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 控制台写开关的契约测试：allow-write=false 时，所有写入口必须 403 且没有副作用。
 *
 * <p>控制台没有鉴权，allow-write 是唯一的安全闸；任何一个新写接口漏写 requireWrite()
 * 都会造成“暂停消费/重放 DLT/改预算/改实验开关”被直接暴露，因此这里逐入口锁住。
 */
@ExtendWith(MockitoExtension.class)
class ConsoleWriteGateTest {

    @Mock
    private AdAdminService adAdminService;
    @Mock
    private DltQueryService dltQueryService;
    @Mock
    private DltReplayAdminService dltReplayAdminService;
    @Mock
    private DltReplayJob dltReplayJob;
    @Mock
    private BudgetReconJob budgetReconJob;
    @Mock
    private ReconReportStore reconReportStore;
    @Mock
    private KafkaLagQueryService kafkaLagQueryService;
    @Mock
    private KafkaListenerEndpointRegistry registry;

    private BidPulseProperties props;
    private ConsoleAdsController ads;
    private ConsoleOpsController ops;
    private ConsoleKafkaController kafka;
    private ConsoleExperimentsController experiments;

    @BeforeEach
    void setUp() {
        props = new BidPulseProperties();
        props.getConsole().setAllowWrite(false);
        ads = new ConsoleAdsController(adAdminService, props);
        ops = new ConsoleOpsController(dltQueryService, dltReplayAdminService,
                dltReplayJob, budgetReconJob, reconReportStore, props);
        kafka = new ConsoleKafkaController(kafkaLagQueryService, registry, props);
        experiments = new ConsoleExperimentsController(props);
    }

    @Test
    @DisplayName("广告创建/更新/状态/预算全部 403 且不触达 AdAdminService")
    void adWritesForbidden() {
        assertForbidden(() -> ads.create(new AdUpsertRequest()));
        assertForbidden(() -> ads.update(1L, new AdUpsertRequest()));
        assertForbidden(() -> ads.changeStatus(1L, 1));
        assertForbidden(() -> ads.updateBudget(1L, new BigDecimal("100.00")));
        verifyNoInteractions(adAdminService);
    }

    @Test
    @DisplayName("DLT 重放/对账全部 403 且不触达任务或 Kafka")
    void opsWritesForbidden() {
        assertForbidden(() -> ops.replay(10));
        assertForbidden(() -> ops.replayOne(0, 0));
        assertForbidden(ops::runRecon);
        verifyNoInteractions(dltQueryService, dltReplayAdminService, dltReplayJob,
                budgetReconJob, reconReportStore);
    }

    @Test
    @DisplayName("消费组暂停/恢复 403 且不触达监听容器")
    void kafkaWritesForbidden() {
        assertForbidden(kafka::pause);
        assertForbidden(kafka::resume);
        verifyNoInteractions(kafkaLagQueryService, registry);
    }

    @Test
    @DisplayName("实验开关写入 403 且配置保持不变")
    void experimentWritesForbidden() {
        assertForbidden(() -> experiments.update(
                new ConsoleExperimentsController.ExperimentsUpdate(false, true, false, 5)));

        assertEquals(true, props.getFallback().isEnabled());
        assertEquals(false, props.getFallback().isForce());
        assertEquals(true, props.getFrequency().isEnabled());
        assertEquals(3, props.getFrequency().getMaxPerHour());
    }

    private static void assertForbidden(Executable action) {
        BizException e = assertThrows(BizException.class, action);
        assertEquals(403, e.getStatus());
    }
}
