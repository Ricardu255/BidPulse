package com.bidpulse.infra.kafka;

import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Kafka 位点与数据库事务的对齐工具。
 *
 * <p><b>为什么不能直接 ack</b>：消费方法上标了 {@code @Transactional}，而
 * {@code ack-mode=manual_immediate} 下 {@code acknowledge()} 会<b>立刻</b>提交位点。
 * 若随后事务提交失败，位点已经推进 → 这条事件再也不会重投，计费流水永久丢失，
 * 与「计费事件不可丢」的设计前提直接矛盾。
 *
 * <p>因此把 ack 注册到 {@link TransactionSynchronization#afterCommit()}：
 * 事务提交成功才推进位点；回滚时不 ack，位点仍在原处，Kafka 会重投同一条事件，
 * 由 {@code processed_event} 的幂等去重保证业务只生效一次。
 *
 * <p>没有事务上下文时（例如单元测试直调）退化为立即 ack，保持原语义。
 */
public final class AckAfterCommit {

    private AckAfterCommit() {
    }

    public static void ack(Acknowledgment ack) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ack.acknowledge();
                }
            });
        } else {
            ack.acknowledge();
        }
    }
}
