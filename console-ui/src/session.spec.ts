import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FALLBACK_AD_ID } from '@/constants'

function stubStorage(adId: string | null): void {
  vi.stubGlobal('localStorage', {
    getItem: vi.fn((key: string) => (key.endsWith('adId') ? adId : null)),
    setItem: vi.fn(),
  })
}

describe('session', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('falls back to the demo ad when stored id is the fallback ad', async () => {
    stubStorage(String(FALLBACK_AD_ID))
    const { session } = await import('./session')
    expect(session.adId).toBe(110)
  })

  it('does not persist the fallback ad id', async () => {
    stubStorage('110')
    const { session, remember } = await import('./session')
    remember(FALLBACK_AD_ID, ' u-1 ')
    expect(session.adId).toBe(110)
    expect(session.userId).toBe('u-1')
    expect(localStorage.setItem).not.toHaveBeenCalledWith('bidpulse.console.adId', String(FALLBACK_AD_ID))
  })
})
