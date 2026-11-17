import type {
  AdInfo,
  AdTarget,
  AdView,
  BidResult,
  BidTrace,
  ExperimentsView,
  ReconReport,
  TraceCandidate,
  TraceCandidateResult,
  TraceOutcome,
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
    creativeUrl: `https://cdn.bidpulse-demo.com/creative/${id}.jpg`,
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

/** 只保留最近若干条，避免长时间开着 mock 页面时无限增长。 */
const TRACE_STORE_LIMIT = 200

/** 记录一份轨迹供按 requestId 回查；写入集中在这里，生成函数保持纯粹。 */
export function rememberTrace(trace: BidTrace): void {
  traceStore.set(trace.requestId, trace)
  while (traceStore.size > TRACE_STORE_LIMIT) {
    const oldest = traceStore.keys().next().value
    if (oldest === undefined) break
    traceStore.delete(oldest)
  }
}

/** 非胜出候选的终局：固定让第 3 条演示「频次超限」，其余按预算不足处理。 */
function loseResult(index: number): TraceCandidateResult {
  return index === 2 ? 'FREQ_CAPPED' : 'INSUFFICIENT'
}

/** 从候选终局反推兜底原因码，避免「表里全是频次超限、标题却写预算耗尽」。 */
function fallbackReasonOf(candidates: TraceCandidate[]): string {
  const freqCapped = candidates.some((candidate) => candidate.result === 'FREQ_CAPPED')
  const budgetShort = candidates.some((candidate) => candidate.result === 'INSUFFICIENT')
  if (freqCapped && budgetShort) return 'BUDGET_AND_FREQ_EXHAUSTED'
  if (freqCapped) return 'FREQ_CAPPED'
  return 'BUDGET_EXHAUSTED'
}

/**
 * 生成一份与竞价结果自洽的 mock 轨迹。
 *
 * <p>`fallback` 必须由调用方显式传入：只看 `winner == null` 无法区分
 * 「兜底广告」与「兜底关闭时返回的空广告（EMPTY）」，两者的轨迹并不相同。
 */
export function mockTrace(
  requestId: string,
  winner: AdInfo | null,
  payPrice: number | null,
  priceMode: number,
  fallback = false,
): BidTrace {
  const candidates: TraceCandidate[] = CANDIDATES.map((candidate, index) => {
    const isWinner = !fallback && winner !== null && candidate.adId === winner.adId
    return {
      adId: candidate.adId,
      bid: candidate.bid,
      // 成交价只属于胜出者；其余候选记录自己的出价。
      // 早先统一 +0.01 会让「成交价高于自己的出价」，与后端语义相反。
      price: isWinner ? payPrice : candidate.bid,
      result: isWinner ? 'SUCCESS' : loseResult(index),
    }
  })
  const outcome: TraceOutcome = fallback ? 'FALLBACK' : winner !== null ? 'WIN' : 'EMPTY'
  const fallbackReason = fallback ? fallbackReasonOf(candidates) : null
  const steps: TraceStep[] = [
    { stage: '检索', detail: `命中候选 ${CANDIDATES.length} 条`, costMs: 8 },
    { stage: '过滤', detail: `定向过滤后 ${CANDIDATES.length} 条`, costMs: 0 },
    { stage: '预热', detail: `候选预算 key 已就绪（${CANDIDATES.length} 条）`, costMs: 0 },
  ]
  if (outcome === 'FALLBACK') {
    steps.push({ stage: '兜底', detail: `返回兜底广告：${fallbackReason}`, costMs: 12 })
  } else if (outcome === 'WIN' && winner !== null) {
    steps.push({
      stage: '成交',
      detail: `${priceMode === 1 ? '二价' : '一价'}成交 ${payPrice} 元（胜出价 ${winner.bid}）`,
      costMs: 12,
    })
  } else {
    steps.push({ stage: '空广告', detail: '没有候选可成交，且兜底已关闭', costMs: 12 })
  }
  return {
    requestId,
    ts: Date.now(),
    outcome,
    fallbackReason,
    candidateCnt: CANDIDATES.length,
    matchCnt: CANDIDATES.length,
    winnerAdId: winner?.adId ?? null,
    winBid: winner?.bid ?? null,
    payPrice,
    priceMode: outcome === 'WIN' ? priceMode : 2,
    candidates,
    steps,
    timings: { dbMs: 8, redisMs: 12, totalMs: 21 },
  }
}

export function result(
  winner: AdInfo | null,
  payPrice: number | null,
  priceMode: number,
  fallback = false,
): BidResult {
  const requestId = `mock-${Date.now().toString(36)}`
  const trace = mockTrace(requestId, fallback ? null : winner, payPrice, priceMode, fallback)
  rememberTrace(trace)
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
    trace,
  }
}
