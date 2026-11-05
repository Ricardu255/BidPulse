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
 */
export type PollingTask = (ctx: PollingContext) => boolean | void | Promise<boolean | void>

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
  let seq = 0

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
    // 定时刷新不叠加：上一轮还没回来就跳过，手动刷新不受此限制
    if (scheduled && scheduledInFlight) return

    const mySeq = ++seq
    const isCurrent = (): boolean => mySeq === seq
    if (scheduled) scheduledInFlight = true

    try {
      const result = await task({ isCurrent, scheduled })
      if (!isCurrent()) return
      if (result === false) {
        markFailed(new Error('refresh failed'))
      } else {
        markUpdated()
      }
    } catch (e) {
      if (isCurrent()) markFailed(e)
    } finally {
      if (scheduled) scheduledInFlight = false
    }
  }

  const stop = (): void => {
    if (timer) {
      clearInterval(timer)
      timer = undefined
    }
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
