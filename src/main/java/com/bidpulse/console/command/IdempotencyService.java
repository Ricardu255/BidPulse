package com.bidpulse.console.command;

import com.bidpulse.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 控制台写操作幂等键。
 *
 * <p><b>为什么需要</b>：DLT 重放、对账、暂停/恢复消费都是「重复执行有副作用」的操作。
 * 双击、浏览器重试、网关重发都会让同一个意图执行两次——重放会把消息再投一遍，
 * 对账会重复写报告。前端每次点击生成一个 {@code Idempotency-Key}，服务端用
 * Redis {@code SET NX PX} 原子占位：10 分钟内同 key 第二次到达直接 409。
 *
 * <p><b>降级策略</b>：Redis 不可用时 <b>fail-open</b>——宁可失去幂等保护，也不能让
 * 运维操作整体不可用；此时只记 warn，操作照常执行。键不存在（老客户端/脚本）时
 * 同样直接放行，保持接口向后兼容。
 *
 * <p><b>失败不释放</b>：操作执行失败后 key 仍保留到 TTL 结束。失败重试请换一个新 key，
 * 避免「同一个 key 先失败、后成功」被误判为重复请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** 幂等窗口：足够覆盖用户重试，又不会长期占用 Redis 内存 */
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_KEY_LENGTH = 128;

    private final StringRedisTemplate redis;

    /**
     * 占位一个幂等键；同一 scope + key 在 TTL 内重复出现时抛 409。
     *
     * @param scope 操作标识（如 {@code dlt-replay}），不同操作的相同 key 互不影响
     * @param key   客户端传入的 Idempotency-Key；为空时不做幂等检查
     */
    public void requireFresh(String scope, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw BizException.badRequest("Idempotency-Key 长度不能超过 " + MAX_KEY_LENGTH);
        }

        String redisKey = "idem:" + scope + ":" + trimmed;
        try {
            Boolean first = redis.opsForValue().setIfAbsent(redisKey, "1", TTL);
            if (Boolean.FALSE.equals(first)) {
                throw BizException.conflict("重复请求：该 Idempotency-Key 已在 10 分钟内执行过");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：幂等是增强能力，Redis 故障不应让运维入口整体不可用
            log.warn("幂等键检查失败（fail-open，继续执行）scope={} key={}: {}",
                    scope, trimmed, e.getMessage());
        }
    }
}
