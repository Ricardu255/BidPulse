import { ApiError } from '../client'
import type { AdDetail, AdQueryParams, AdUpsertRequest, AdView, PageResult } from '../types'
import { MOCK_ADS, MOCK_TARGETS, delay } from './shared'

export async function mockAds(params: AdQueryParams): Promise<PageResult<AdView>> {
  await delay(140)
  const filtered = MOCK_ADS.filter((ad) => {
    if (params.status != null && ad.status !== params.status) return false
    if (params.advertiserId != null && ad.advertiserId !== params.advertiserId) return false
    if (params.titleLike && !ad.title.includes(params.titleLike)) return false
    return true
  })
  const pageNum = Math.max(1, params.pageNum)
  const pageSize = Math.max(1, params.pageSize)
  const start = (pageNum - 1) * pageSize
  return {
    total: filtered.length,
    pageNum,
    pageSize,
    pages: Math.max(1, Math.ceil(filtered.length / pageSize)),
    list: filtered.slice(start, start + pageSize),
  }
}

export async function mockAdDetail(id: number): Promise<AdDetail> {
  await delay(120)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (!ad) throw new ApiError(`广告不存在: ${id}`, 404)
  return { ad, targets: MOCK_TARGETS.get(ad.id) ?? [] }
}

export async function mockCreateAd(req: AdUpsertRequest): Promise<{ id: number }> {
  await delay(160)
  const id = Math.max(...MOCK_ADS.map((ad) => ad.id)) + 1
  const now = '2026-10-31 11:00:00'
  MOCK_ADS.unshift({
    id,
    advertiserId: req.advertiserId ?? 1,
    title: req.title,
    creativeUrl: req.creativeUrl,
    categoryCode: req.categoryCode,
    bid: req.bid,
    dailyBudget: req.dailyBudget,
    status: req.status ?? 1,
    createdAt: now,
    updatedAt: now,
  })
  MOCK_TARGETS.set(id, req.targets)
  return { id }
}

export async function mockUpdateAd(
  id: number,
  req: AdUpsertRequest,
): Promise<{ id: number; updated: boolean }> {
  await delay(160)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (!ad) throw new ApiError(`广告不存在: ${id}`, 404)
  ad.title = req.title
  ad.creativeUrl = req.creativeUrl
  ad.categoryCode = req.categoryCode
  ad.bid = req.bid
  ad.dailyBudget = req.dailyBudget
  if (req.status != null) ad.status = req.status
  ad.updatedAt = '2026-10-31 11:00:00'
  MOCK_TARGETS.set(id, req.targets)
  return { id, updated: true }
}

export async function mockChangeAdStatus(id: number, status: number): Promise<{ id: number; status: number }> {
  await delay(100)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (!ad) throw new ApiError(`广告不存在: ${id}`, 404)
  ad.status = status
  return { id, status }
}

export async function mockUpdateAdBudget(
  id: number,
  dailyBudget: number,
): Promise<{ id: number; dailyBudget: number }> {
  await delay(100)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (!ad) throw new ApiError(`广告不存在: ${id}`, 404)
  ad.dailyBudget = dailyBudget
  return { id, dailyBudget }
}
