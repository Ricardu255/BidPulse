import { computed, reactive, ref, type ComputedRef, type UnwrapNestedRefs } from 'vue'

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/**
 * 表单脏检查：保留最近一次“服务端确认值”，支持加载、标记保存和回滚。
 */
export function useDirtyForm<T extends object>(initial: T): {
  form: UnwrapNestedRefs<T>
  dirty: ComputedRef<boolean>
  load: (value: T) => void
  markSaved: () => void
  reset: () => void
} {
  const form = reactive(clone(initial)) as UnwrapNestedRefs<T>
  const saved = ref<T>(clone(initial))
  const dirty = computed(() => JSON.stringify(form) !== JSON.stringify(saved.value))

  function load(value: T): void {
    const next = clone(value)
    Object.assign(form, next)
    saved.value = clone(next)
  }

  function markSaved(): void {
    saved.value = clone(form as T)
  }

  function reset(): void {
    Object.assign(form, clone(saved.value))
  }

  return { form, dirty, load, markSaved, reset }
}
