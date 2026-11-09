package com.bidpulse.infra.kafka;

import com.bidpulse.event.BidEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旁路发送失败的可观测性单测。
 *
 * <p>发送失败不影响 /bid 返回，但「静默失败」会让计费流水/小时报表悄悄缺数据。
 * 这里的契约：失败（异步回调失败 / 序列化异常）必须让 {@code bidpulse.kafka.publish.failures} +1。
 */
class BidEventPublisherTest {

    private static final String TOPIC = "bid_event";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publish_success_doesNotCountFailure() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new BidEventPublisher(kafkaTemplate, objectMapper, meterRegistry, TOPIC).publish(event());

        assertEquals(0.0, failureCount());
    }

    @Test
    void publish_asyncSendFailure_countsFailure() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new BidEventPublisher(kafkaTemplate, objectMapper, meterRegistry, TOPIC).publish(event());

        assertEquals(1.0, failureCount());
    }

    @Test
    void publish_serializationFailure_countsFailureWithoutSending() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("cannot serialize"));
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

        new BidEventPublisher(kafkaTemplate, failingMapper, meterRegistry, TOPIC).publish(event());

        assertEquals(1.0, failureCount());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    private double failureCount() {
        return meterRegistry.counter(BidEventPublisher.PUBLISH_FAILURES_METRIC).count();
    }

    private BidEvent event() {
        return BidEvent.builder()
                .eventId("evt-1")
                .requestId("req-1")
                .ts(1_700_000_000_000L)
                .region("CN-310000")
                .adId(1001L)
                .advertiserId(10)
                .hasAd(true)
                .build();
    }
}
