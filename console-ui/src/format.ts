export function money(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `¥${Number(value).toFixed(2)}`
}

/**
 * 保留 4 位小数。
 *
 * 诊断页（数据浏览）里的金额列是 DECIMAL(10,4)/DECIMAL(12,4)，
 * 用 2 位小数的 {@link money} 会把 0.0049 显示成 ¥0.00，
 * 亚分级别的对账差异正好被抹掉——而那正是运营来这一页要找的东西。
 */
export function money4(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `¥${Number(value).toFixed(4)}`
}

export function cents(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `¥${(Number(value) / 100).toFixed(2)}`
}

export function ttlLabel(value: number | null | undefined): string {
  if (value == null) return '—'
  if (value === -1) return '无过期'
  if (value < 0) return '未创建'
  if (value < 60) return `${value} 秒`
  // 先取整再判边界：3599 秒若按「< 3600」走分钟分支会显示成「60 分钟」，
  // 与 3600 秒的「1 小时」读起来自相矛盾。
  const minutes = Math.round(value / 60)
  if (minutes < 60) return `${minutes} 分钟`
  if (value < 86400) return `${Number((value / 3600).toFixed(1))} 小时`
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  return hours > 0 ? `${days} 天 ${hours} 小时` : `${days} 天`
}

/**
 * 后端返回的时间串统一成 `YYYY-MM-DD HH:mm:ss`。
 *
 * <p>直接 `value.replace(...)` 在字段为 null 时会让整个表格渲染抛错，
 * 因此空值先归一到占位符。
 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

export function percent(part: number | null | undefined, total: number | null | undefined): number {
  if (part == null || total == null || total <= 0) return 0
  return Math.max(0, Math.min(100, (Number(part) / Number(total)) * 100))
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
