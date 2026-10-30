package com.bidpulse.console.dto;

import java.util.Map;

/**
 * DLT 消息只读视图。
 *
 * <p>{@code value} 可能很大，服务端截断到 2000 字符；完整报文需到 Kafka 工具或日志里查。
 */
public record DltMessageView(
        String topic,
        int partition,
        long offset,
        String key,
        String value,
        int replayCount,
        String timestamp,
        Map<String, String> headers) {
}
