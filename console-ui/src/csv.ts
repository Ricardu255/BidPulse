export function toCsv(columns: string[], rows: Record<string, unknown>[]): string {
  const escape = (value: unknown): string => {
    let text = String(value ?? '')
    // 防止 CSV 公式注入：以 = + - @ 或控制字符开头的单元格加前缀单引号
    if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
    return `"${text.replace(/"/g, '""')}"`
  }

  const lines = [
    columns.map(escape).join(','),
    ...rows.map((row) => columns.map((column) => escape(row[column])).join(',')),
  ]
  // BOM 让 Excel 正确识别 UTF-8 中文
  return `﻿${lines.join('\r\n')}`
}
