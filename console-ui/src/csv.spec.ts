import { describe, expect, it } from 'vitest'
import { toCsv } from './csv'

describe('csv', () => {
  it('writes a BOM, header and CRLF rows', () => {
    const csv = toCsv(['ad_id', 'pay_price'], [{ ad_id: 110, pay_price: 1.01 }])
    expect(csv.startsWith('﻿')).toBe(true)
    expect(csv).toContain('"ad_id","pay_price"\r\n"110","1.01"')
  })

  it('escapes quotes, commas, newlines and nulls', () => {
    const csv = toCsv(['v'], [{ v: 'a"b,c\nd' }, { v: null }])
    expect(csv).toContain('"a""b,c\nd"')
    expect(csv).toContain('""')
  })

  it('neutralizes spreadsheet formulas', () => {
    const csv = toCsv(['v'], [{ v: '=cmd' }, { v: '+1' }, { v: '-1' }, { v: '@x' }, { v: '\t=cmd' }])
    expect(csv).toContain('"\'=cmd"')
    expect(csv).toContain('"\'+1"')
    expect(csv).toContain('"\'-1"')
    expect(csv).toContain('"\'@x"')
    expect(csv).toContain('"\'\t=cmd"')
  })

  it('renders missing columns as empty cells', () => {
    const csv = toCsv(['a', 'b'], [{ a: 1 }])
    expect(csv).toContain('"1",""')
  })
})
