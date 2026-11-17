import type {
  BidTrace,
  DataQueryParams,
  DataView,
  OverviewView,
  RecentBidView,
} from '../types'
import { ApiError } from '../client'
import { CANDIDATES, MOCK_ADS, delay, mockTrace, rememberTrace, state, traceStore } from './shared'
import { FALLBACK_AD_ID } from '@/constants'

export async function mockOverview(): Promise<OverviewView> {
  await delay(160)
  const trend = Array.from({ length: 12 }, (_, index) => ({
    minute: `${String(10 + Math.floor(index / 6)).padStart(2, '0')}:${String((index % 6) * 5).padStart(2, '0')}`,
    bids: 160 + ((index * 37) % 90),
    fallback: index % 4 === 0 ? 3 : 1,
  }))
  const bidsToday = trend.reduce((sum, point) => sum + point.bids, 0) * 3
  const fallbackToday = 128
  return {
    bidsToday,
    fallbackToday,
    fallbackRate: fallbackToday / bidsToday,
    spendToday: 1876.42,
    p95CostMs: 48,
    totalAds: 200,
    activeAds: 192,
    pausedAds: 5,
    exhaustedAds: 3,
    kafkaLag: state.paused ? 224 : 0,
    health: { overall: 'UP', db: 'UP', redis: 'UP', kafka: 'UP' },
    trend,
  }
}

/** 最近竞价列表的固定剧本：每 5 行留一行「没有轨迹」，每 6/13 行演示兜底。 */
interface RecentBidShape {
  fallback: boolean
  winnerId: number | null
  payPrice: number | null
}

function recentBidShape(index: number): RecentBidShape {
  if (index === 6 || index === 13) {
    return { fallback: true, winnerId: null, payPrice: null }
  }
  if (index % 3 === 0) {
    return { fallback: false, winnerId: 106, payPrice: 0.81 }
  }
  return { fallback: false, winnerId: 110, payPrice: 1.01 }
}

export async function mockRecentBids(limit = 20): Promise<RecentBidView[]> {
  await delay(140)
  return Array.from({ length: Math.min(limit, 50) }, (_, index) => {
    const { fallback, winnerId, payPrice } = recentBidShape(index)
    const winner = CANDIDATES.find((candidate) => candidate.adId === winnerId) ?? null
    const requestId = `mock-${(1730000000000 + index).toString(36)}`
    const hasTrace = index % 5 !== 4
    // 只有 hasTrace=true 的行才写入轨迹库：否则列表说「没有轨迹」，
    // 按 requestId 却又能查出一份，flag 与数据自相矛盾。
    if (hasTrace) {
      rememberTrace(mockTrace(requestId, fallback ? null : winner, payPrice, fallback ? 2 : 1, fallback))
    }
    return {
      requestId,
      adId: fallback ? FALLBACK_AD_ID : winnerId,
      winBid: fallback ? null : winner?.bid ?? null,
      payPrice,
      hasAd: true,
      costMs: 28 + ((index * 7) % 24),
      bidAt: `2026-10-31 10:${String(20 + (index % 40)).padStart(2, '0')}:00`,
      hasTrace,
    }
  })
}

/**
 * 按 requestId 回查 mock 轨迹。
 *
 * <p>未记录时抛 404，与后端「未知 requestId / 无轨迹一律 404」的契约保持一致；
 * 早先这里凭空生成一份「胜出轨迹」，导致 mock 永远复现不出空态与 404 分支。
 */
export async function mockBidTrace(requestId: string): Promise<BidTrace> {
  await delay(120)
  const trace = traceStore.get(requestId)
  if (!trace) {
    throw new ApiError(`该竞价没有决策轨迹：${requestId}`, 404)
  }
  return trace
}

/** bid_log 演示行：每 3 行换一个胜出广告，第 7 行演示兜底（无成交价）。 */
function mockBidLogValues(index: number): { ad_id: number; win_bid: number | null; pay_price: number | null } {
  if (index === 6) {
    return { ad_id: FALLBACK_AD_ID, win_bid: null, pay_price: null }
  }
  const winnerId = index % 3 === 0 ? 106 : 110
  return winnerId === 106
    ? { ad_id: 106, win_bid: 1.0, pay_price: 0.81 }
    : { ad_id: 110, win_bid: 1.5, pay_price: 1.01 }
}

export async function mockData(table: string, params: DataQueryParams): Promise<DataView> {
  await delay(160)
  const limit = Math.min(params.limit, 200)
  if (table === 'bid_log') {
    return {
      table,
      columns: ['request_id', 'ad_id', 'win_bid', 'pay_price', 'has_ad', 'cost_ms', 'bid_at'],
      rows: Array.from({ length: Math.min(limit, 10) }, (_, index) => ({
        request_id: `mock-${(1730000000000 + index).toString(36)}`,
        ...mockBidLogValues(index),
        has_ad: 1,
        cost_ms: 28 + index * 3,
        bid_at: `2026-10-31 10:${String(20 + index).padStart(2, '0')}:00`,
      })),
    }
  }
  if (table === 'billing_event') {
    return {
      table,
      columns: ['event_id', 'ad_id', 'advertiser_id', 'pay_price', 'ts', 'event_time'],
      rows: Array.from({ length: Math.min(limit, 8) }, (_, index) => ({
        event_id: `evt-${index}`,
        ad_id: 110,
        advertiser_id: 11,
        pay_price: 1.01,
        ts: 1793380000000 + index,
        event_time: '2026-10-31 10:20:00',
      })),
    }
  }
  if (table === 'bid_stat_hourly') {
    return {
      table,
      columns: ['stat_hour', 'ad_id', 'impressions', 'spend'],
      rows: [{ stat_hour: '2026-10-31 10:00', ad_id: 110, impressions: 12, spend: 12.12 }],
    }
  }
  if (table === 'ad_freq') {
    return {
      table,
      columns: ['ad_id', 'cnt'],
      rows: MOCK_ADS.slice(0, 5).map((ad) => ({ ad_id: ad.id, cnt: 3 })),
    }
  }
  return {
    table: 'processed_event',
    columns: ['event_id', 'grp', 'created_at'],
    rows: ['billing-recon', 'hourly-preagg', 'freq-antifraud'].map((grp) => ({
      event_id: 'mock-event-1',
      grp,
      created_at: '2026-10-31 10:20:00',
    })),
  }
}
