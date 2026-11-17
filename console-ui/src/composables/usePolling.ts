import { onUnmounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'

/**
 * 轮询任务上下文。
 *
 * <p>页面在 `await` 之后、写入本地状态之前必须检查 {@link isCurrent}：
 * 快速切换筛选条件时，旧请求可能后返回，直接把新数据覆盖掉。
 */
export interface PollingContext {
  /** 本次调用是否仍是最新一次；false 表示已有更新的请求发出，应丢弃本次结果 */
  isCurrent: () => boolean
  /** 是否由定时器触发。后台轮询失败通常只标记陈旧，不弹 toast */
  scheduled: boolean
}

/**
 * 轮询任务。
 *
 * <p>返回 `false` 或抛异常表示本次刷新失败：控制器会把数据标记为「可能已过期」，
 * 但保留上一次成功的数据继续展示。
 *
 * <p>返回 `'skipped'` 表示「这一轮什么都没做」（例如没选中广告、参数不合法）：
 * 既不标记失败，也不推进「更新于」时间戳——否则页面会一直显示一个
 * 其实没有发出任何请求的新鲜时间。
 */
export type PollingTask = (
  ctx: PollingContext,
) => boolean | 'skipped' | void | Promise<boolean | 'skipped' | void>

export interface PollingController {
  /** 定时器是否在运行（与单次请求是否在途无关） */
  running: Ref<boolean>
  /** 最近一次成功刷新时间（毫秒时间戳） */
  lastUpdatedAt: Ref<number | null>
  /** 最近一次刷新失败后置 true；下一次成功自动清除 */
  stale: Ref<boolean>
  /** 最近一次失败原因，成功刷新后清空 */
  lastError: Ref<unknown>
  /** 立即刷新（手动触发，不跳过在途请求） */
  refresh: () => Promise<void>
  start: () => void
  stop: () => void
  restart: () => void
}

/**
 * 轮询控制器（纯逻辑，不依赖组件生命周期，可直接单测）。
 *
 * <p><b>不重叠</b>：定时器触发时若上一次定时刷新仍未返回，直接跳过本轮，
 * 避免慢接口把请求越堆越多。
 *
 * <p><b>latest-wins</b>：每次调用都有递增序号；任务通过 `ctx.isCurrent()`
 * 判断自己是否已被更新的一次调用取代，从而丢弃过期响应。
 *
 * <p><b>陈旧标记</b>：失败时只把 `stale` 置 true，不清空已有数据；
 * 页面据此显示「数据可能已过期」，而不是让用户误以为数字仍然实时。
 */
export function createPollingController(task: PollingTask, intervalMs: number): PollingController {
  const running = ref(false)
  const lastUpdatedAt = ref<number | null>(null)
  const stale = ref(false)
  const lastError = ref<unknown>(null)

  let timer: ReturnType<typeof setInterval> | undefined
  let scheduledInFlight = false
  let scheduledStartedAt = 0
  let seq = 0

  /**
   * 悬挂判定阈值：正常接口远达不到，只有真正卡死（无超时的半开连接）才会越过。
   */
  const IN_FLIGHT_DEADLINE_MS = 5 * 60_000

  const markUpdated = (): void => {
    lastUpdatedAt.value = Date.now()
    stale.value = false
    lastError.value = null
  }

  const markFailed = (error: unknown): void => {
    stale.value = true
    lastError.value = error
  }

  async function execute(scheduled: boolean): Promise<void> {
    // 定时刷新不叠加：上一轮还没回来就跳过，手动刷新不受此限制。
    // 但「不叠加」不能退化成「永久卡死」：请求悬挂（上游半开连接、客户端没有超时）
    // 时 scheduledInFlight 永远不会被清掉，之后每一轮都被跳过，
    // 页面看起来自动刷新还开着，实际数据早已冻结。
    // 超过 5 分钟仍没返回就认为那一轮已经死了：只释放标记，由下一轮重试，
    // 不在同一时刻再发一个请求——否则就真的变成并发重叠了。
    if (scheduled && scheduledInFlight) {
      if (Date.now() - scheduledStartedAt < IN_FLIGHT_DEADLINE_MS) return
      console.warn(
        `[usePolling] 上一轮定时刷新已悬挂 ${Math.round(IN_FLIGHT_DEADLINE_MS / 1000)}s 以上，释放占用标记`,
      )
      scheduledInFlight = false
    }

    const mySeq = ++seq
    const isCurrent = (): boolean => mySeq === seq
    let ownsScheduled = false
    if (scheduled) {
      scheduledInFlight = true
      scheduledStartedAt = Date.now()
      ownsScheduled = true
    }

    try {
      const result = await task({ isCurrent, scheduled })
      if (!isCurrent()) return
      if (result === 'skipped') return
      if (result === false) {
        markFailed(new Error('refresh failed'))
      } else {
        markUpdated()
      }
    } catch (e) {
      if (isCurrent()) markFailed(e)
    } finally {
      // 只有仍持有这一轮标记的调用才能清除；被「放行」后的新一轮有自己的 mySeq
      if (ownsScheduled && mySeq === seq) scheduledInFlight = false
    }
  }

  const stop = (): void => {
    if (timer) {
      clearInterval(timer)
      timer = undefined
    }
    // 停止时一并释放悬挂标记，否则重新开启后第一轮会被历史请求永久挡住
    scheduledInFlight = false
    running.value = false
  }

  const start = (): void => {
    stop()
    running.value = true
    timer = setInterval(() => {
      void execute(true)
    }, intervalMs)
  }

  return {
    running,
    lastUpdatedAt,
    stale,
    lastError,
    refresh: () => execute(false),
    start,
    stop,
    restart: start,
  }
}

/**
 * 页面级轮询：只在 enabled 为真时启动定时器，组件卸载时自动停止。
 *
 * <p>不立即执行 task——首屏数据由页面自己的 onMounted 拉取，轮询只负责后续刷新。
 */
export function usePolling(
  task: PollingTask,
  intervalMs: number,
  enabled: MaybeRefOrGetter<boolean> = true,
): PollingController {
  const controller = createPollingController(task, intervalMs)

  watch(
    () => toValue(enabled),
    (on) => {
      if (on) controller.start()
      else controller.stop()
    },
    { immediate: true },
  )
  onUnmounted(controller.stop)

  return controller
}
