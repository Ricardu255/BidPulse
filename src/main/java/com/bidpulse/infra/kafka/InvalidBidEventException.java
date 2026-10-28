package com.bidpulse.infra.kafka;

/**
 * 无法处理、且重试也不会成功的竞价事件（JSON 解析失败 / 缺少 eventId 等）。
 *
 * <p>与「DB 暂时不可用」这类可重试故障区分开：这类报文无论重试多少次都不会成功，
 * 因此在 {@code KafkaErrorHandlingConfig} 里注册为<b>不可重试异常</b>，
 * 由错误处理器直接投递死信主题 —— 既不无谓地拖住分区，也不静默丢弃。
 */
public class InvalidBidEventException extends RuntimeException {

    public InvalidBidEventException(String message) {
        super(message);
    }

    public InvalidBidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
