/**
 * 生成一次性幂等键，随危险写操作发给后端。
 *
 * <p>浏览器支持 `crypto.randomUUID` 时直接使用；旧环境/非安全上下文退化为
 * 时间戳 + 随机串。键只要求在同一操作的作用域内唯一，不承担安全职责。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** 危险写操作的请求初始化：POST + 幂等键。 */
export function idempotentPost(): RequestInit {
  return {
    method: 'POST',
    headers: { 'Idempotency-Key': newIdempotencyKey() },
  }
}
