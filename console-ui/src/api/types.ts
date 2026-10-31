export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
  wallMs?: number | null
}

export interface BidRequest {
  slotCode: string
  region: string
  age?: number
  gender?: number
  ageBucket?: string
  interests?: string
  categoryCode?: string
  userId?: string
}

export interface AdInfo {
  adId: number
  advertiserId: number
  title: string
  categoryCode: string
  bid: number
}

export interface BidResult {
  requestId: string
  hasAd: boolean
  fallback: boolean
  winner: AdInfo | null
  candidateCnt: number
  matchCnt: number
  topCandidates: AdInfo[]
  priceMode: number
  payPrice: number | null
}

export interface BudgetView {
  adId: number
  title: string
  status: number
  bid: number
  dailyBudget: number
  remainingCents: number | null
  spentCents: number | null
  ttlSeconds: number | null
  seeded: boolean
}

export interface FrequencyView {
  userId: string | null
  adId: number
  enabled: boolean
  count: number | null
  maxPerHour: number
  ttlSeconds: number | null
}

export interface KafkaPartitionView {
  partition: number
  committed: number
  end: number
  lag: number
}

export interface KafkaGroupView {
  group: string
  exists: boolean
  partitionCount: number
  totalLag: number
  partitions: KafkaPartitionView[]
}

export interface TrendPoint {
  minute: string
  bids: number
  fallback: number
}

export interface OverviewView {
  bidsToday: number
  fallbackToday: number
  fallbackRate: number
  spendToday: number
  p95CostMs: number
  totalAds: number
  activeAds: number
  pausedAds: number
  exhaustedAds: number
  kafkaLag: number
  health: Record<string, string>
  trend: TrendPoint[]
}

export interface RecentBidView {
  requestId: string
  adId: number | null
  winBid: number | null
  payPrice: number | null
  hasAd: boolean
  costMs: number
  bidAt: string
}

export interface AdView {
  id: number
  advertiserId: number
  title: string
  creativeUrl: string
  categoryCode: string
  bid: number
  dailyBudget: number
  status: number
  createdAt: string
  updatedAt: string
}

export interface AdTarget {
  dimType: number
  dimValue: string
}

export interface AdUpsertRequest {
  advertiserId?: number
  title: string
  creativeUrl: string
  categoryCode: string
  bid: number
  dailyBudget: number
  status?: number
  targets: AdTarget[]
}

export interface AdDetail {
  ad: AdView
  targets: AdTarget[]
}

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  pages: number
  list: T[]
}

export interface AdQueryParams {
  pageNum: number
  pageSize: number
  status?: number
  advertiserId?: number
  titleLike?: string
}

export interface DltMessageView {
  topic: string
  partition: number
  offset: number
  key: string | null
  value: string | null
  replayCount: number
  processed: boolean
  timestamp: string | null
  headers: Record<string, string>
}

export interface DltReplayStats {
  replayed: number
  skippedInvalid: number
  skippedExhausted: number
}

export interface ReconMismatch {
  adId: number
  redisCents: number
  dbCents: number
  diffCents: number
  direction: string
  action: string
}

export interface ReconReport {
  lastRunAt: string
  status: string
  adCount: number
  alerted: number
  repaired: number
  durationMs: number
  message: string | null
  mismatches: ReconMismatch[]
}

export interface DataView {
  table: string
  columns: string[]
  rows: Record<string, unknown>[]
}

export interface DataQueryParams {
  limit: number
  adId?: number
  requestId?: string
}

export interface ExperimentsView {
  fallbackEnabled: boolean
  fallbackForce: boolean
  frequencyEnabled: boolean
  frequencyMaxPerHour: number
  detailSyncInsert: boolean
  consoleAllowWrite: boolean
}

export interface ExperimentsUpdate {
  fallbackEnabled?: boolean
  fallbackForce?: boolean
  frequencyEnabled?: boolean
  frequencyMaxPerHour?: number
}

export interface BurstRow {
  index: number
  winnerId: number | null
  hasAd: boolean
  fallback: boolean
  payPrice: number | null
  priceMode: number
  wallMs: number | null
  error: string | null
}
