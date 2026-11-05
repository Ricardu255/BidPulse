import type {
  AdInfo,
  AdTarget,
  AdView,
  BidResult,
  BidTrace,
  ExperimentsView,
  ReconReport,
  TraceCandidate,
  TraceStep,
} from '../types'

export const delay = (ms = 220) => new Promise((resolve) => setTimeout(resolve, ms))

export const CANDIDATES: AdInfo[] = [
  { adId: 110, advertiserId: 11, title: '创意标题-110', categoryCode: 'travel', bid: 1.5 },
  { adId: 106, advertiserId: 7, title: '创意标题-106', categoryCode: 'ecom', bid: 1.0 },
  { adId: 123, advertiserId: 24, title: '创意标题-123', categoryCode: 'game', bid: 0.8 },
  { adId: 145, advertiserId: 46, title: '创意标题-145', categoryCode: 'news', bid: 0.6 },
]

export const MOCK_ADS: AdView[] = Array.from({ length: 12 }, (_, index) => {
  const id = 100 + index
  const status = index % 7 === 0 ? 2 : index % 11 === 0 ? 3 : 1
  return {
    id,
    advertiserId: (index % 5) + 1,
    title: `创意标题-${id}`,
    creativeUrl: `https://cdn.cdn.bidpulse-demo.com/creative/${id}.jpg`,
    categoryCode: ['travel', 'ecom', 'game', 'news', 'finance'][index % 5],
    bid: Number((0.6 + (index % 6) * 0.2).toFixed(2)),
    dailyBudget: [1000, 5000, 10000, 50000][index % 4],
    status,
    createdAt: '2026-10-01 10:00:00',
    updatedAt: '2026-10-31 09:12:00',
  }
})

export const MOCK_TARGETS = new Map<number, AdTarget[]>(
  MOCK_ADS.map((ad) => [
    ad.id,
    [
      { dimType: 1, dimValue: '110000' },
      { dimType: 2, dimValue: '25-30' },
      { dimType: 3, dimValue: 'male' },
      { dimType: 4, dimValue: ad.categoryCode },
    ],
  ]),
)

export const initialExperiments: ExperimentsView = {
  fallbackEnabled: true,
  fallbackForce: false,
  frequencyEnabled: true,
  frequencyMaxPerHour: 3,
  detailSyncInsert: false,
  consoleAllowWrite: true,
}

export const initialReconReport: ReconReport = {
  lastRunAt: '2026-10-31 10:30:00',
  status: 'OK',
  adCount: 128,
  alerted: 1,
  repaired: 2,
  durationMs: 340,
  message: null,
  mismatches: [
    {
      adId: 110,
      redisCents: 303,
      dbCents: 202,
      diffCents: 101,
      direction: 'REDIS_AHEAD',
      action: 'ALERT',
    },
    {
      adId: 106,
      redisCents: 100,
      dbCents: 200,
      diffCents: -100,
      direction: 'DB_AHEAD',
      action: 'REPAIRED',
    },
  ],
}

/** 全局 mock 状态：多个领域模块共享。 */
export const state = {
  paused: false,
  experiments: { ...initialExperiments },
  reconReport: { ...initialReconReport },
  /** key = `${userId}:${adId}`，匿名流量不计数 */
  bidCounts: new Map<string, number>(),
}

/** mock 轨迹缓存：最近竞价列表点「轨迹」时按 requestId 回查。 */
export const traceStore = new Map<string, BidTrace>()

/** 生成一份与竞价结果自洽的 mock 轨迹，供 Playground 与最近竞价抽屉展示。 */
export function mockTrace(
  requestId: string,
  winner: AdInfo | null,
  payPrice: number | null,
  priceMode: number,
): BidTrace {
  const fallback = winner == null
  const candidates: TraceCandidate[] = CANDIDATES.map((candidate, index) => ({
    adId: candidate.adId,
    bid: candidate.bid,
    price: !fallback && candidate.adId === winner.adId
      ? payPrice
      : Number((candidate.bid + 0.01).toFixed(2)),
    result: !fallback && candidate.adId === winner.adId
      ? 'SUCCESS'
      : index === 2
        ? 'FREQ_CAPPED'
        : 'INSUFFICIENT',
  }))
  const steps: TraceStep[] = [
    { stage: '检索', detail: `命中候选 ${CANDIDATES.length} 条`, costMs: 8 },
    { stage: '过滤', detail: `定向过滤后 ${CANDIDATES.length} 条`, costMs: 0 },
    { stage: '预热', detail: `候选预算 key 已就绪（${CANDIDATES.length} 条）`, costMs: 0 },
    fallback
      ? { stage: '兜底', detail: '返回兜底广告：BUDGET_EXHAUSTED', costMs: 12 }
      : {
          stage: '成交',
          detail: `${priceMode === 1 ? '二价' : '一价'}成交 ${payPrice} 元（胜出价 ${winner.bid}）`,
          costMs: 12,
        },
  ]
  const trace: BidTrace = {
    requestId,
    ts: Date.now(),
    outcome: fallback ? 'FALLBACK' : 'WIN',
    fallbackReason: fallback ? 'BUDGET_EXHAUSTED' : null,
    candidateCnt: CANDIDATES.length,
    matchCnt: CANDIDATES.length,
    winnerAdId: winner?.adId ?? null,
    winBid: winner?.bid ?? null,
    payPrice,
    priceMode: fallback ? 2 : priceMode,
    candidates,
    steps,
    timings: { dbMs: 8, redisMs: 12, totalMs: 21 },
  }
  traceStore.set(requestId, trace)
  return trace
}

export function result(
  winner: AdInfo | null,
  payPrice: number | null,
  priceMode: number,
  fallback = false,
): BidResult {
  const requestId = `mock-${Date.now().toString(36)}`
  return {
    requestId,
    hasAd: winner != null,
    fallback,
    winner,
    candidateCnt: CANDIDATES.length,
    matchCnt: CANDIDATES.length,
    topCandidates: CANDIDATES,
    priceMode,
    payPrice,
    trace: mockTrace(requestId, fallback ? null : winner, payPrice, priceMode),
  }
}
