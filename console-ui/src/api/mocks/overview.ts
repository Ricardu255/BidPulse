import type {
  DataQueryParams,
  DataView,
  OverviewView,
  RecentBidView,
} from '../types'
import { MOCK_ADS, delay, state } from './shared'

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

export async function mockRecentBids(limit = 20): Promise<RecentBidView[]> {
  await delay(140)
  return Array.from({ length: Math.min(limit, 50) }, (_, index) => {
    const fallback = index === 6 || index === 13
    const winner = fallback ? null : index % 3 === 0 ? 106 : 110
    return {
      requestId: `mock-${(1730000000000 + index).toString(36)}`,
      adId: fallback ? 999999 : winner,
      winBid: fallback ? null : winner === 110 ? 1.5 : 1.0,
      payPrice: fallback ? null : winner === 110 ? 1.01 : 0.81,
      hasAd: true,
      costMs: 28 + ((index * 7) % 24),
      bidAt: `2026-10-31 10:${String(20 + (index % 40)).padStart(2, '0')}:00`,
    }
  })
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
        ad_id: index === 6 ? 999999 : index % 3 === 0 ? 106 : 110,
        win_bid: index === 6 ? null : index % 3 === 0 ? 1.0 : 1.5,
        pay_price: index === 6 ? null : index % 3 === 0 ? 0.81 : 1.01,
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
