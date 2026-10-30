import { describe, expect, it } from 'vitest'
import { cents, errorText, money, percent, ttlLabel } from './format'

describe('format', () => {
  it('formats yuan and cents', () => {
    expect(money(1.5)).toBe('¥1.50')
    expect(money(null)).toBe('—')
    expect(cents(101)).toBe('¥1.01')
    expect(cents(null)).toBe('—')
  })

  it('formats ttl sentinel values', () => {
    expect(ttlLabel(7200)).toBe('7200s')
    expect(ttlLabel(-1)).toBe('无过期')
    expect(ttlLabel(-2)).toBe('未创建')
    expect(ttlLabel(null)).toBe('—')
  })

  it('clamps percentages', () => {
    expect(percent(50, 100)).toBe(50)
    expect(percent(150, 100)).toBe(100)
    expect(percent(-1, 100)).toBe(0)
    expect(percent(1, 0)).toBe(0)
  })

  it('extracts error messages', () => {
    expect(errorText(new Error('boom'))).toBe('boom')
    expect(errorText('plain')).toBe('plain')
  })
})
