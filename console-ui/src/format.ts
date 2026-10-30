export function money(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `¥${Number(value).toFixed(2)}`
}

export function cents(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `¥${(Number(value) / 100).toFixed(2)}`
}

export function ttlLabel(value: number | null | undefined): string {
  if (value == null) return '—'
  if (value === -1) return '无过期'
  if (value < 0) return '未创建'
  return `${value}s`
}

export function percent(part: number | null | undefined, total: number | null | undefined): number {
  if (part == null || total == null || total <= 0) return 0
  return Math.max(0, Math.min(100, (Number(part) / Number(total)) * 100))
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
