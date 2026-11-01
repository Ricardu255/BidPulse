import { onUnmounted, toValue, watch, type MaybeRefOrGetter } from 'vue'

/**
 * 页面级轮询：只在 enabled 为真时启动定时器，组件卸载时自动停止。
 *
 * <p>不立即执行 task——首屏数据由页面自己的 onMounted 拉取，轮询只负责后续刷新。
 */
export function usePolling(
  task: () => void | Promise<void>,
  intervalMs: number,
  enabled: MaybeRefOrGetter<boolean> = true,
): { restart: () => void; stop: () => void } {
  let timer: ReturnType<typeof setInterval> | undefined

  const stop = (): void => {
    if (timer) {
      clearInterval(timer)
      timer = undefined
    }
  }

  const restart = (): void => {
    stop()
    if (toValue(enabled)) {
      timer = setInterval(() => {
        void task()
      }, intervalMs)
    }
  }

  watch(() => toValue(enabled), restart, { immediate: true })
  onUnmounted(stop)

  return { restart, stop }
}
