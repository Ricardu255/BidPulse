import type {
  AdDetail,
  AdInfo,
  AdQueryParams,
  AdTarget,
  AdView,
  BidRequest,
  BidResult,
  BudgetView,
  DltMessageView,
  DltReplayStats,
  FrequencyView,
  KafkaGroupView,
  OverviewView,
  PageResult,
  RecentBidView,
  ReconReport,
} from './types'

const CANDIDATES: AdInfo[] = [
  { adId: 110, advertiserId: 11, title: '创意标题-110', categoryCode: 'travel', bid: 1.5 },
  { adId: 106, advertiserId: 7, title: '创意标题-106', categoryCode: 'ecom', bid: 1.0 },
  { adId: 123, advertiserId: 24, title: '创意标题-123', categoryCode: 'game', bid: 0.8 },
  { adId: 145, advertiserId: 46, title: '创意标题-145', categoryCode: 'news', bid: 0.6 },
]

let bidCount = 0
let paused = false

const delay = (ms = 220) => new Promise((resolve) => setTimeout(resolve, ms))

function result(winner: AdInfo | null, payPrice: number | null, priceMode: number, fallback = false): BidResult {
  return {
    requestId: `mock-${Date.now().toString(36)}`,
    hasAd: winner != null,
    fallback,
    winner,
    candidateCnt: CANDIDATES.length,
    matchCnt: CANDIDATES.length,
    topCandidates: CANDIDATES,
    priceMode,
    payPrice,
  }
}

/** 模拟频次上限：同一 userId 前 3 次 110 胜出，第 4 次起跳过 110。 */
export async function mockRunBid(payload: BidRequest): Promise<{ result: BidResult; wallMs: number }> {
  await delay()
  const hasUser = Boolean(payload.userId?.trim())
  const capped = hasUser && bidCount >= 3
  if (!capped) {
    bidCount += 1
  }
  const winner = capped ? CANDIDATES[1] : CANDIDATES[0]
  const payPrice = capped ? 0.81 : 1.01
  return { result: result(winner, payPrice, 1), wallMs: 30 + Math.round(Math.random() * 12) }
}

export async function mockBudget(adId: number): Promise<BudgetView> {
  await delay(120)
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
  const hasUser = Boolean(userId?.trim())
  return {
    userId: userId ?? null,
    adId,
    enabled: hasUser,
    count: hasUser ? Math.min(bidCount, 3) : null,
    maxPerHour: 3,
    ttlSeconds: hasUser ? 7100 : null,
  }
}

export async function mockKafkaGroups(): Promise<KafkaGroupView[]> {
  await delay(120)
  const base = paused ? [128, 64, 32] : [0, 0, 0]
  return [
    { group: 'billing-recon', exists: true, partitionCount: 3, totalLag: base[0], partitions: partitions(base[0]) },
    { group: 'hourly-preagg', exists: true, partitionCount: 3, totalLag: base[1], partitions: partitions(base[1]) },
    { group: 'freq-antifraud', exists: true, partitionCount: 3, totalLag: base[2], partitions: partitions(base[2]) },
  ]
}

export async function mockPause(): Promise<{ status: string }> {
  await delay(100)
  paused = true
  return { status: 'paused' }
}

export async function mockResume(): Promise<{ status: string }> {
  await delay(100)
  paused = false
  return { status: 'resumed' }
}

function partitions(totalLag: number) {
  const first = Math.ceil(totalLag / 2)
  return [
    { partition: 0, committed: 5100, end: 5100 + first, lag: first },
    { partition: 1, committed: 3400, end: 3400 + totalLag - first, lag: totalLag - first },
    { partition: 2, committed: 1700, end: 1700, lag: 0 },
  ]
}

const MOCK_ADS: AdView[] = Array.from({ length: 12 }, (_, index) => {
  const id = 100 + index
  const status = index % 7 === 0 ? 2 : index % 11 === 0 ? 3 : 1
  return {
    id,
    advertiserId: (index % 5) + 1,
    title: `创意标题-${id}`,
    creativeUrl: `https://cdn.example.com/creative/${id}.jpg`,
    categoryCode: ['travel', 'ecom', 'game', 'news', 'finance'][index % 5],
    bid: Number((0.6 + (index % 6) * 0.2).toFixed(2)),
    dailyBudget: [1000, 5000, 10000, 50000][index % 4],
    status,
    createdAt: '2026-10-01 10:00:00',
    updatedAt: '2026-10-31 09:12:00',
  }
})

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
    kafkaLag: paused ? 224 : 0,
    health: { overall: 'UP', db: 'UP', redis: 'UP', kafka: 'UP' },
    trend,
  }
}

export async function mockRecentBids(limit = 20): Promise<RecentBidView[]> {
  await delay(140)
  return Array.from({ length: Math.min(limit, 15) }, (_, index) => {
    const fallback = index === 6 || index === 13
    const winner = fallback ? null : (index % 3 === 0 ? 106 : 110)
    return {
      requestId: `mock-${(1730000000000 + index).toString(36)}`,
      adId: fallback ? 999999 : winner,
      winBid: fallback ? null : winner === 110 ? 1.5 : 1.0,
      payPrice: fallback ? null : winner === 110 ? 1.01 : 0.81,
      hasAd: true,
      costMs: 28 + ((index * 7) % 24),
      bidAt: `2026-10-31 10:${String(20 + index).padStart(2, '0')}:00`,
    }
  })
}

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
  const ad = MOCK_ADS.find((item) => item.id === id) ?? MOCK_ADS[0]
  const targets: AdTarget[] = [
    { dimType: 1, dimValue: '110000' },
    { dimType: 2, dimValue: '25-30' },
    { dimType: 3, dimValue: 'male' },
    { dimType: 4, dimValue: 'travel' },
  ]
  return { ad, targets }
}

export async function mockChangeAdStatus(id: number, status: number): Promise<{ id: number; status: number }> {
  await delay(100)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (ad) ad.status = status
  return { id, status }
}

export async function mockUpdateAdBudget(id: number, dailyBudget: number): Promise<{ id: number; dailyBudget: number }> {
  await delay(100)
  const ad = MOCK_ADS.find((item) => item.id === id)
  if (ad) ad.dailyBudget = dailyBudget
  return { id, dailyBudget }
}

let mockReconReport: ReconReport = {
  lastRunAt: '2026-10-31 10:30:00',
  status: 'OK',
  adCount: 128,
  alerted: 1,
  repaired: 2,
  durationMs: 340,
  message: null,
}

export async function mockDltMessages(limit = 20): Promise<DltMessageView[]> {
  await delay(160)
  return Array.from({ length: Math.min(limit, 6) }, (_, index) => ({
    topic: 'bid_event.DLT',
    partition: index % 3,
    offset: 2048 + index,
    key: String(110 + index),
    value: JSON.stringify({
      eventId: `mock-dlt-${index}`,
      requestId: `req-${index}`,
      adId: 110 + index,
      payPrice: 1.01,
    }),
    replayCount: index % 3,
    timestamp: `2026-10-31 10:2${index}:00`,
    headers: {
      'x-replay-count': String(index % 3),
      'kafka_dlt-original-topic': 'bid_event',
    },
  }))
}

export async function mockReplayDlt(maxRecords: number): Promise<DltReplayStats> {
  await delay(300)
  const replayed = Math.min(maxRecords, 3)
  return { replayed, skippedInvalid: 1, skippedExhausted: 1 }
}

export async function mockRecon(): Promise<ReconReport> {
  await delay(120)
  return { ...mockReconReport }
}

export async function mockRunRecon(): Promise<ReconReport> {
  await delay(400)
  mockReconReport = {
    ...mockReconReport,
    lastRunAt: '2026-10-31 10:45:00',
    adCount: 130,
    alerted: 0,
    repaired: 1,
    durationMs: 290,
  }
  return { ...mockReconReport }
}
