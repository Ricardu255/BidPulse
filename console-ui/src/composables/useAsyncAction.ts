import { ref, type Ref } from 'vue'

/**
 * 把一次异步操作收敛成 loading + error + run，避免每个页面重复 try/catch/finally。
 *
 * <p>调用方可以传 onError 做 toast/清理；run 失败时返回 undefined，不向上抛，
 * 便于页面继续渲染旧的可用数据。
 */
export function useAsyncAction<Args extends unknown[], Result>(
  action: (...args: Args) => Promise<Result>,
  onError?: (error: unknown) => void,
): {
  loading: Ref<boolean>
  error: Ref<unknown>
  run: (...args: Args) => Promise<Result | undefined>
} {
  const loading = ref(false)
  const error = ref<unknown>(null)

  async function run(...args: Args): Promise<Result | undefined> {
    loading.value = true
    error.value = null
    try {
      return await action(...args)
    } catch (e) {
      error.value = e
      onError?.(e)
      return undefined
    } finally {
      loading.value = false
    }
  }

  return { loading, error, run }
}
