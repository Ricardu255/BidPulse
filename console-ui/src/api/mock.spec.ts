import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('mock api', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('caps frequency after three bids for the same user', async () => {
    const { mockRunBid } = await import('./mock')
    const payload = { slotCode: 'slot-01', region: '110000', age: 28, gender: 1, userId: 'u-1' }

    const first = await mockRunBid(payload)
    const second = await mockRunBid(payload)
    const third = await mockRunBid(payload)
    const fourth = await mockRunBid(payload)

    expect(first.result.winner?.adId).toBe(110)
    expect(second.result.winner?.adId).toBe(110)
    expect(third.result.winner?.adId).toBe(110)
    expect(fourth.result.winner?.adId).toBe(106)
    expect(fourth.result.payPrice).toBe(0.81)
  })

  it('filters and paginates ads', async () => {
    const { mockAds } = await import('./mock')
    const page = await mockAds({ pageNum: 1, pageSize: 5, status: 1 })
    expect(page.list.length).toBeLessThanOrEqual(5)
    expect(page.list.every((ad) => ad.status === 1)).toBe(true)
    expect(page.total).toBeGreaterThan(0)
  })

  it('returns data explorer rows and CSV-friendly columns', async () => {
    const { mockData } = await import('./mock')
    const data = await mockData('bid_log', { limit: 3 })
    expect(data.columns).toContain('request_id')
    expect(data.rows.length).toBe(3)
  })

  it('updates experiment switches', async () => {
    const { mockExperiments, mockUpdateExperiments } = await import('./mock')
    const before = await mockExperiments()
    expect(before.frequencyEnabled).toBe(true)
    const after = await mockUpdateExperiments({ frequencyEnabled: false, frequencyMaxPerHour: 7 })
    expect(after.frequencyEnabled).toBe(false)
    expect(after.frequencyMaxPerHour).toBe(7)
  })
})
