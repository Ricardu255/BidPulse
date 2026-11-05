import { afterEach, describe, expect, it, vi } from 'vitest'
import { idempotentPost, newIdempotencyKey } from './idempotency'

describe('idempotency key', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('creates unique keys', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()))
    expect(keys.size).toBe(50)
  })

  it('falls back when crypto.randomUUID is unavailable', () => {
    vi.stubGlobal('crypto', {})
    expect(newIdempotencyKey()).toMatch(/^idem-/)
  })

  it('attaches the key header to POST requests', () => {
    const init = idempotentPost()
    expect(init.method).toBe('POST')
    const headers = new Headers(init.headers)
    expect(headers.get('Idempotency-Key')).toBeTruthy()
  })
})
