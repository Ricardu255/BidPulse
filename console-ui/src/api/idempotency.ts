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

/**
 * 一次「用户意图」的幂等键。
 *
 * <p>后端按 Idempotency-Key 做 10 分钟去重，但这只在<b>同一个意图的多次发送复用同一个键</b>
 * 时才成立。早先每次调用 `idempotentPost()` 都现生成一个键，于是双击、超时重试、
 * 响应丢失后重按都会拿到新键，服务端各执行一遍——去重窗口形同虚设。
 *
 * <p>用法：在组件作用域创建一个 handle（一个操作一个），发送时取 `current()`；
 * 操作成功后 `reset()`，下一次点击就是新意图；<b>失败时不要 reset</b>，
 * 这样用户重试仍然落在同一个键上，由服务端吸收重复执行。
 */
export interface IdempotencyKeyHandle {
  /** 当前意图的键；首次调用时生成，之后保持不变。 */
  current(): string
  /** 结束当前意图，下一次 `current()` 生成新键。 */
  reset(): void
}

export function idempotencyKey(): IdempotencyKeyHandle {
  let key: string | null = null
  return {
    current(): string {
      if (key === null) {
        key = newIdempotencyKey()
      }
      return key
    },
    reset(): void {
      key = null
    },
  }
}

/**
 * 危险写操作的请求初始化：POST + 幂等键。
 *
 * @param key 调用方持有的意图键；省略时退化为「一次性键」，
 *            仅能防止中间层重发完全相同的请求，挡不住双击与重试。
 */
export function idempotentPost(key?: string): RequestInit {
  return {
    method: 'POST',
    headers: { 'Idempotency-Key': key ?? newIdempotencyKey() },
  }
}
