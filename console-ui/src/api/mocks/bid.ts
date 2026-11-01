import { ApiError } from '../client'
import type { BidRequest, BidResult, BudgetView, FrequencyView } from '../types'
import { CANDIDATES, MOCK_ADS, delay, result, state } from './shared'

/** 模拟频次上限：同一用户对 110 的前 N 次胜出，超过后跳过 110。 */
export async function mockRunBid(payload: BidRequest): Promise<{ result: BidResult; wallMs: number }> {
  await delay()
  const userId = payload.userId?.trim()
  const max = Math.max(1, state.experiments.frequencyMaxPerHour)
  const key = userId ? `${userId}:110` : ''
  const used = userId && state.experiments.frequencyEnabled ? (state.bidCounts.get(key) ?? 0) : 0
  const capped = Boolean(userId) && state.experiments.frequencyEnabled && used >= max

  if (userId && state.experiments.frequencyEnabled && !capped) {
    state.bidCounts.set(key, used + 1)
  }
  const winner = capped ? CANDIDATES[1] : CANDIDATES[0]
  const payPrice = capped ? 0.81 : 1.01
  return { result: result(winner, payPrice, 1), wallMs: 30 + Math.round(Math.random() * 12) }
}

export async function mockBudget(adId: number): Promise<BudgetView> {
  await delay(120)
  if (!MOCK_ADS.some((ad) => ad.id === adId)) {
    throw new ApiError(`广告不存在: ${adId}`, 404)
  }
  return {
    adId,
    title: `创意标题-${adId}`,
    status: 1,
    bid: 1.5,
    dailyBudget: 1000,
    remainingCents: 99700,
    spentCents: 300,
    ttlSeconds: 172800,
    seeded: true,
  }
}

export async function mockFrequency(adId: number, userId?: string): Promise<FrequencyView> {
  await delay(120)
  if (!MOCK_ADS.some((ad) => ad.id === adId)) {
    throw new ApiError(`广告不存在: ${adId}`, 404)
  }
  const hasUser = Boolean(userId?.trim())
  const enabled = hasUser && state.experiments.frequencyEnabled
  const count = enabled ? (state.bidCounts.get(`${userId}:${adId}`) ?? null) : null
  return {
    userId: userId ?? null,
    adId,
    enabled,
    count,
    maxPerHour: Math.max(1, state.experiments.frequencyMaxPerHour),
    ttlSeconds: enabled ? 7100 : null,
  }
}
