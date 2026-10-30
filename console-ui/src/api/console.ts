import { request, requestEnvelope } from './client'
import {
  mockAdDetail,
  mockAds,
  mockBudget,
  mockChangeAdStatus,
  mockCreateAd,
  mockData,
  mockDltMessages,
  mockExperiments,
  mockFrequency,
  mockKafkaGroups,
  mockOverview,
  mockPause,
  mockRecentBids,
  mockRecon,
  mockReplayDlt,
  mockResume,
  mockRunBid,
  mockRunRecon,
  mockUpdateAd,
  mockUpdateAdBudget,
  mockUpdateExperiments,
} from './mock'
import type {
  AdDetail,
  AdQueryParams,
  AdUpsertRequest,
  AdView,
  BidRequest,
  BidResult,
  BudgetView,
  BurstRow,
  DataQueryParams,
  DataView,
  DltMessageView,
  DltReplayStats,
  ExperimentsUpdate,
  ExperimentsView,
  FrequencyView,
  KafkaGroupView,
  OverviewView,
  PageResult,
  RecentBidView,
  ReconReport,
} from './types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

export async function runBid(payload: BidRequest): Promise<{ result: BidResult; wallMs: number | null }> {
  if (USE_MOCK) {
    return mockRunBid(payload)
  }
  const envelope = await requestEnvelope<BidResult>('/bids', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  return { result: envelope.data, wallMs: envelope.wallMs ?? null }
}

export async function runBurst(payload: BidRequest, times = 4): Promise<BurstRow[]> {
  const rows: BurstRow[] = []
  for (let i = 1; i <= times; i += 1) {
    try {
      const { result, wallMs } = await runBid(payload)
      rows.push({
        index: i,
        winnerId: result.winner?.adId ?? null,
        hasAd: result.hasAd,
        fallback: result.fallback,
        payPrice: result.payPrice,
        priceMode: result.priceMode,
        wallMs,
        error: null,
      })
    } catch (e) {
      rows.push({
        index: i,
        winnerId: null,
        hasAd: false,
        fallback: false,
        payPrice: null,
        priceMode: -1,
        wallMs: null,
        error: e instanceof Error ? e.message : String(e),
      })
    }
  }
  return rows
}

export function getBudget(adId: number): Promise<BudgetView> {
  if (USE_MOCK) return mockBudget(adId)
  return request<BudgetView>(`/budget?adId=${encodeURIComponent(String(adId))}`)
}

export function getFrequency(adId: number, userId?: string): Promise<FrequencyView> {
  if (USE_MOCK) return mockFrequency(adId, userId)
  const user = userId?.trim()
  const suffix = user ? `&userId=${encodeURIComponent(user)}` : ''
  return request<FrequencyView>(`/frequency?adId=${encodeURIComponent(String(adId))}${suffix}`)
}

export function getKafkaGroups(): Promise<KafkaGroupView[]> {
  if (USE_MOCK) return mockKafkaGroups()
  return request<KafkaGroupView[]>('/kafka/groups')
}

export function pauseConsumers(): Promise<{ status: string }> {
  if (USE_MOCK) return mockPause()
  return request<{ status: string }>('/kafka/pause', { method: 'POST' })
}

export function resumeConsumers(): Promise<{ status: string }> {
  if (USE_MOCK) return mockResume()
  return request<{ status: string }>('/kafka/resume', { method: 'POST' })
}

export function getOverview(): Promise<OverviewView> {
  if (USE_MOCK) return mockOverview()
  return request<OverviewView>('/overview')
}

export function getRecentBids(limit = 20): Promise<RecentBidView[]> {
  if (USE_MOCK) return mockRecentBids(limit)
  return request<RecentBidView[]>(`/bids/recent?limit=${encodeURIComponent(String(limit))}`)
}

export function getAds(params: AdQueryParams): Promise<PageResult<AdView>> {
  if (USE_MOCK) return mockAds(params)
  const search = new URLSearchParams()
  search.set('pageNum', String(params.pageNum))
  search.set('pageSize', String(params.pageSize))
  if (params.status != null) search.set('status', String(params.status))
  if (params.advertiserId != null) search.set('advertiserId', String(params.advertiserId))
  if (params.titleLike) search.set('titleLike', params.titleLike)
  return request<PageResult<AdView>>(`/ads?${search.toString()}`)
}

export function getAdDetail(id: number): Promise<AdDetail> {
  if (USE_MOCK) return mockAdDetail(id)
  return request<AdDetail>(`/ads/${id}`)
}

export function createAd(req: AdUpsertRequest): Promise<{ id: number }> {
  if (USE_MOCK) return mockCreateAd(req)
  return request<{ id: number }>('/ads', { method: 'POST', body: JSON.stringify(req) })
}

export function updateAd(id: number, req: AdUpsertRequest): Promise<{ id: number; updated: boolean }> {
  if (USE_MOCK) return mockUpdateAd(id, req)
  return request<{ id: number; updated: boolean }>(`/ads/${id}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function changeAdStatus(id: number, status: number): Promise<{ id: number; status: number }> {
  if (USE_MOCK) return mockChangeAdStatus(id, status)
  return request<{ id: number; status: number }>(`/ads/${id}/status?status=${status}`, { method: 'PUT' })
}

export function updateAdBudget(id: number, dailyBudget: number): Promise<{ id: number; dailyBudget: number }> {
  if (USE_MOCK) return mockUpdateAdBudget(id, dailyBudget)
  return request<{ id: number; dailyBudget: number }>(
    `/ads/${id}/budget?dailyBudget=${encodeURIComponent(String(dailyBudget))}`,
    { method: 'PUT' },
  )
}

export function getDltMessages(limit = 20): Promise<DltMessageView[]> {
  if (USE_MOCK) return mockDltMessages(limit)
  return request<DltMessageView[]>(`/dlt/messages?limit=${encodeURIComponent(String(limit))}`)
}

export function replayDlt(maxRecords = 100): Promise<DltReplayStats> {
  if (USE_MOCK) return mockReplayDlt(maxRecords)
  return request<DltReplayStats>(
    `/dlt/replay?maxRecords=${encodeURIComponent(String(maxRecords))}`,
    { method: 'POST' },
  )
}

export function getReconReport(): Promise<ReconReport | null> {
  if (USE_MOCK) return mockRecon()
  return request<ReconReport | null>('/recon')
}

export function runRecon(): Promise<ReconReport> {
  if (USE_MOCK) return mockRunRecon()
  return request<ReconReport>('/recon/run', { method: 'POST' })
}

export function getData(table: string, params: DataQueryParams): Promise<DataView> {
  if (USE_MOCK) return mockData(table, params)
  const search = new URLSearchParams()
  search.set('limit', String(params.limit))
  if (params.adId != null) search.set('adId', String(params.adId))
  if (params.requestId) search.set('requestId', params.requestId)
  return request<DataView>(`/data/${encodeURIComponent(table)}?${search.toString()}`)
}

export function getExperiments(): Promise<ExperimentsView> {
  if (USE_MOCK) return mockExperiments()
  return request<ExperimentsView>('/experiments')
}

export function updateExperiments(update: ExperimentsUpdate): Promise<ExperimentsView> {
  if (USE_MOCK) return mockUpdateExperiments(update)
  return request<ExperimentsView>('/experiments', {
    method: 'PUT',
    body: JSON.stringify(update),
  })
}
