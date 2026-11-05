<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import StaleNotice from '@/components/StaleNotice.vue'
import BidTraceTimeline from '@/components/BidTraceTimeline.vue'
import OverviewHealthCard from '@/components/overview/OverviewHealthCard.vue'
import OverviewDistributionCard from '@/components/overview/OverviewDistributionCard.vue'
import OverviewTrendChart from '@/components/overview/OverviewTrendChart.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBidTrace, getOverview, getRecentBids } from '@/api/console'
import { ApiError } from '@/api/client'
import type { BidTrace, OverviewView, RecentBidView } from '@/api/types'
import { errorText, money } from '@/format'
import { usePolling } from '@/composables/usePolling'
import { FALLBACK_AD_ID } from '@/constants'

const router = useRouter()
const overview = ref<OverviewView | null>(null)
const recent = ref<RecentBidView[]>([])
const recentAll = ref<RecentBidView[]>([])
const loading = ref(false)
const auto = ref(true)
const recentDrawer = ref(false)
const traceVisible = ref(false)
const traceLoading = ref(false)
const trace = ref<BidTrace | null>(null)
const traceRequestId = ref('')

const fallbackPercent = computed(() => `${((overview.value?.fallbackRate ?? 0) * 100).toFixed(2)}%`)

function resultLabel(row: RecentBidView): string {
  if (!row.hasAd) return '空广告'
  return row.adId === FALLBACK_AD_ID ? '兜底' : '胜出'
}

function resultType(row: RecentBidView): 'success' | 'warning' | 'info' {
  if (!row.hasAd) return 'info'
  return row.adId === FALLBACK_AD_ID ? 'warning' : 'success'
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 12)}…` : value
}

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

async function openRecent(): Promise<void> {
  recentDrawer.value = true
  try {
    recentAll.value = await getRecentBids(50)
  } catch (e) {
    notifyError(e)
  }
}

/** 明细异步刷盘（≤200ms），刚发起的竞价可能短暂 404；重试 3 次再放弃 */
async function loadTraceWithRetry(requestId: string): Promise<BidTrace> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await getBidTrace(requestId)
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 404 || attempt >= 2) throw e
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
  }
}

let traceSeq = 0
async function openTrace(row: RecentBidView): Promise<void> {
  const mySeq = ++traceSeq
  traceVisible.value = true
  traceLoading.value = true
  trace.value = null
  traceRequestId.value = row.requestId
  try {
    const result = await loadTraceWithRetry(row.requestId)
    if (mySeq === traceSeq) trace.value = result
  } catch (e) {
    // 404 表示明细还没刷出来或该条没有轨迹，交给弹窗空状态说明，不弹错误 toast
    if (mySeq === traceSeq && (!(e instanceof ApiError) || e.status !== 404)) notifyError(e)
  } finally {
    if (mySeq === traceSeq) traceLoading.value = false
  }
}

// 轮询失败只标记「陈旧」，保留上一次成功的数据；手动刷新才弹 toast
const { stale, lastUpdatedAt, refresh: refreshData } = usePolling(
  async ({ isCurrent, scheduled }) => {
    try {
      const [overviewView, recentRows] = await Promise.all([getOverview(), getRecentBids(15)])
      if (!isCurrent()) return
      overview.value = overviewView
      recent.value = recentRows
    } catch (e) {
      if (isCurrent() && !scheduled) notifyError(e)
      throw e
    }
  },
  5000,
  auto,
)

let manualLoads = 0
function refresh(): void {
  manualLoads += 1
  loading.value = true
  void refreshData().finally(() => {
    manualLoads -= 1
    if (manualLoads === 0) loading.value = false
  })
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <PageHeader
      title="首页"
      description="先看这里：系统是否健康、今天有没有在正常竞价。下面是近 30 分钟趋势和最近请求。"
    >
      <template #actions>
        <div class="actions">
          <el-button :loading="loading" @click="refresh">刷新</el-button>
          <el-switch v-model="auto" active-text="自动刷新（5s）" />
        </div>
      </template>
    </PageHeader>

    <StaleNotice :stale="stale" :last-updated-at="lastUpdatedAt" />

    <div v-if="overview" class="metric-grid six">
      <MetricCard label="今日请求" :value="overview.bidsToday" hint="今日竞价明细记录数" />
      <MetricCard
        label="兜底率"
        :value="fallbackPercent"
        :hint="`${overview.fallbackToday} 次兜底`"
      />
      <MetricCard label="P95 耗时" :value="`${overview.p95CostMs} ms`" hint="今日最近 1000 次竞价" />
      <MetricCard
        label="今日消耗"
        :value="money(overview.spendToday)"
        hint="今日计费流水汇总"
      />
      <MetricCard
        label="活跃广告"
        :value="`${overview.activeAds} / ${overview.totalAds}`"
        :hint="`暂停 ${overview.pausedAds} · 耗尽 ${overview.exhaustedAds}`"
      />
      <MetricCard
        label="Kafka 总积压"
        :value="overview.kafkaLag < 0 ? '—' : overview.kafkaLag"
        hint="三个消费组未消费消息合计"
      />
    </div>

    <el-card class="panel quick-start" shadow="never">
      <div class="quick-start-inner">
        <div>
          <strong>第一次来？按这个顺序看</strong>
          <p>先确认系统状态，再模拟一次竞价，最后看预算/频次和异步链路。</p>
        </div>
        <div class="actions">
          <el-button type="primary" @click="router.push('/playground')">模拟一次竞价</el-button>
          <el-button @click="router.push('/ads')">管理广告</el-button>
          <el-button @click="router.push('/ops')">检查异步链路</el-button>
        </div>
      </div>
    </el-card>

    <el-row v-if="overview" :gutter="16">
      <el-col :xs="24" :md="8">
        <OverviewHealthCard :health="overview.health" />
      </el-col>

      <el-col :xs="24" :md="6">
        <OverviewDistributionCard :recent="recent" />
      </el-col>

      <el-col :xs="24" :md="10">
        <OverviewTrendChart :trend="overview.trend" />
      </el-col>
    </el-row>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>最近竞价</span>
          <el-button size="small" @click="openRecent">查看最近 50 条</el-button>
        </div>
      </template>
      <el-table class="recent-table" :data="recent.slice(0, 5)" size="small">
        <el-table-column prop="bidAt" label="时间" width="170" />
        <el-table-column label="请求 ID" width="150">
          <template #default="{ row }">
            <span class="mono">{{ shortId(row.requestId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="adId" label="广告 ID" width="100" align="right" />
        <el-table-column label="胜出价" width="110" align="right">
          <template #default="{ row }">{{ money(row.winBid) }}</template>
        </el-table-column>
        <el-table-column label="成交价" width="110" align="right">
          <template #default="{ row }">{{ money(row.payPrice) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="resultType(row)" size="small">{{ resultLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ row.costMs }} ms</template>
        </el-table-column>
        <el-table-column label="轨迹" width="80">
          <template #default="{ row }">
            <el-button v-if="row.hasTrace" link type="primary" size="small" @click="openTrace(row)">
              查看
            </el-button>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="recent-mobile">
        <div v-for="row in recent.slice(0, 5)" :key="row.requestId" class="recent-mobile-row">
          <div class="recent-mobile-main">
            <span class="mono">{{ shortId(row.requestId) }}</span>
            <el-tag :type="resultType(row)" size="small">{{ resultLabel(row) }}</el-tag>
          </div>
          <div class="recent-mobile-meta">
            <span>{{ row.bidAt }}</span>
            <strong>{{ money(row.payPrice) }}</strong>
            <span>广告 {{ row.adId }}</span>
            <el-button v-if="row.hasTrace" link type="primary" size="small" @click="openTrace(row)">
              轨迹
            </el-button>
          </div>
        </div>
      </div>
      <p class="hint">
        明细由本地队列异步落库，可能有约 200ms 延迟。结果中的“兜底”表示这次请求没有可投放广告
        （无候选 / 预算耗尽 / 依赖故障 / 超时），系统返回固定广告 ID {{ FALLBACK_AD_ID }} 的保底广告；
        它的出价是配置值，payPrice 为“—”表示不计费。
      </p>
    </el-card>

    <el-drawer v-model="recentDrawer" title="最近竞价" size="760px">
      <el-table :data="recentAll" stripe size="small">
        <el-table-column prop="bidAt" label="时间" width="170" />
        <el-table-column prop="requestId" label="请求 ID" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.requestId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="adId" label="广告 ID" width="100" align="right" />
        <el-table-column label="胜出价" width="100">
          <template #default="{ row }">{{ money(row.winBid) }}</template>
        </el-table-column>
        <el-table-column label="成交价" width="100">
          <template #default="{ row }">{{ money(row.payPrice) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="resultType(row)" size="small">{{ resultLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs }} ms</template>
        </el-table-column>
        <el-table-column label="轨迹" width="80">
          <template #default="{ row }">
            <el-button v-if="row.hasTrace" link type="primary" size="small" @click="openTrace(row)">
              查看
            </el-button>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="traceVisible" title="决策轨迹" width="760px" destroy-on-close>
      <div v-loading="traceLoading" class="trace-dialog-body">
        <BidTraceTimeline v-if="trace" :trace="trace" />
        <el-empty
          v-else-if="!traceLoading"
          description="轨迹暂不可用：明细异步落库，可能稍后才有"
          :image-size="60"
        />
      </div>
      <template #footer>
        <span class="hint mono">{{ traceRequestId }}</span>
      </template>
    </el-dialog>
  </div>
</template>
