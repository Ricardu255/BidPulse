package com.bidpulse.infra.kafka;

/**
 * 同一个 {@code eventId} 在同一消费组对应了不同载荷。
 *
 * <p>这不是「重复投递」（重复投递的指纹相同，可以安全跳过），而是生产端 bug、
 * 手工重放或恶意构造导致的<b>资金语义冲突</b>：若继续处理，账单金额可能被
 * 后到的载荷改写。重试不会让两份不同载荷变得一致，因此继承
 * {@link InvalidBidEventException}，由错误处理器跳过重试、直接投递 DLT。
 *
 * <p>进入 DLT 后需要人工确认哪一份载荷才是事实，再决定补偿方式；不能靠自动重放。
 */
public class BidEventPayloadConflictException extends InvalidBidEventException {

    public BidEventPayloadConflictException(String message) {
        super(message);
    }
}
