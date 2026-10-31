<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getOverview, getRecentBids } from '@/api/console'
import type { OverviewView, RecentBidView } from '@/api/types'
import { errorText, money } from '@/format'

const router = useRouter()
const overview = ref<OverviewView | null>(null)
const recent = ref<RecentBidView[]>([])
const loading = ref(false)
const auto = ref(true)
const recentDrawer = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

const maxBids = computed(() => {
  const points = overview.value?.trend ?? []
  return Math.max(1, ...points.map((point) => point.bids))
})

const fallbackPercent = computed(() => `${((overview.value?.fallbackRate ?? 0) * 100).toFixed(2)}%`)

const healthItems = computed(() =>
  Object.entries(overview.value?.health ?? {}).map(([key, value]) => ({
    key,
    value,
    label: healthLabel(key),
  })),
)

const distribution = computed(() => {
  let win = 0
  let fallback = 0
  let empty = 0
  for (const row of recent.value) {
    if (!row.hasAd) empty += 1
    else if (row.adId === 999999) fallback += 1
    else win += 1
  }
  const total = win + fallback + empty
  const winEnd = total ? (win / total) * 100 : 0
  const fallbackEnd = total ? winEnd + (fallback / total) * 100 : 0
  const gradient = total
    ? `conic-gradient(#5b83a6 0 ${winEnd}%, #b9853c ${winEnd}% ${fallbackEnd}%, #c9d9e6 ${fallbackEnd}% 100%)`
    : 'conic-gradient(#e4edf4 0 100%)'
  return { win, fallback, empty, total, gradient }
})

function barHeight(bids: number): number {
  return Math.max(4, Math.round((bids / maxBids.value) * 110))
}

function healthLabel(key: string): string {
  const labels: Record<string, string> = {
    overall: '整体',
    db: 'MySQL',
    redis: 'Redis',
    kafka: 'Kafka',
    diskSpace: '磁盘',
    ping: 'Ping',
    dbBreaker: 'DB 熔断',
    redisBreaker: 'Redis 熔断',
  }
  return labels[key] ?? key
}

function healthType(value: string): 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'UP' || value === 'CLOSED') return 'success'
  if (value === 'HALF_OPEN') return 'warning'
  if (value === 'DOWN' || value === 'OUT_OF_SERVICE' || value === 'OPEN') return 'danger'
  return 'info'
}

function resultLabel(row: RecentBidView): string {
  if (!row.hasAd) return '空广告'
  return row.adId === 999999 ? '兜底' : '胜出'
}

function resultType(row: RecentBidView): 'success' | 'warning' | 'info' {
  if (!row.hasAd) return 'info'
  return row.adId === 999999 ? 'warning' : 'success'
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 12)}…` : value
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    const [overviewView, recentRows] = await Promise.all([getOverview(), getRecentBids(15)])
    overview.value = overviewView
    recent.value = recentRows
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function stopTimer(): void {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

function startTimer(): void {
  stopTimer()
  if (auto.value) {
    timer = setInterval(refresh, 5000)
  }
}

watch(auto, startTimer)

onMounted(() => {
  refresh()
  startTimer()
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>首页</h1>
        <p>先看这里：系统是否健康、今天有没有在正常竞价。下面是近 30 分钟趋势和最近请求。</p>
      </div>
      <div class="actions">
        <el-button :loading="loading" @click="refresh">刷新</el-button>
        <el-switch v-model="auto" active-text="自动刷新（5s）" />
      </div>
    </header>

    <div v-if="overview" class="metric-grid six">
      <div class="metric">
        <span>今日请求</span>
        <strong>{{ overview.bidsToday }}</strong>
        <small>bid_log 今日行数</small>
      </div>
      <div class="metric">
        <span>兜底率</span>
        <strong>{{ fallbackPercent }}</strong>
        <small>{{ overview.fallbackToday }} 次兜底</small>
      </div>
      <div class="metric">
        <span>P95 耗时</span>
        <strong>{{ overview.p95CostMs }} ms</strong>
        <small>今日最近 1000 条</small>
      </div>
      <div class="metric">
        <span>今日消耗</span>
        <strong>{{ money(overview.spendToday) }}</strong>
        <small>billing_event 汇总</small>
      </div>
      <div class="metric">
        <span>活跃广告</span>
        <strong>{{ overview.activeAds }} / {{ overview.totalAds }}</strong>
        <small>暂停 {{ overview.pausedAds }} · 耗尽 {{ overview.exhaustedAds }}</small>
      </div>
      <div class="metric">
        <span>Kafka 总 lag</span>
        <strong>{{ overview.kafkaLag < 0 ? '—' : overview.kafkaLag }}</strong>
        <small>三个消费组合计</small>
      </div>
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

    <el-row :gutter="16">
      <el-col :xs="24" :md="7">
        <el-card class="panel" shadow="never">
          <template #header>系统健康</template>
          <div class="health-strip">
            <div v-for="item in healthItems" :key="item.key" class="health-chip">
              <span class="health-name">{{ item.label }}</span>
              <el-tag :type="healthType(item.value)" size="small">{{ item.value }}</el-tag>
            </div>
            <el-empty v-if="!healthItems.length" description="暂无健康数据" :image-size="60" />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="7">
        <el-card class="panel" shadow="never">
          <template #header>最近结果分布</template>
          <div class="donut-wrap">
            <div class="donut" :style="{ background: distribution.gradient }">
              <div class="donut-center">
                <strong>{{ distribution.total }}</strong>
                <small>最近竞价</small>
              </div>
            </div>
            <div class="legend">
              <div><span class="legend-dot win"></span>胜出 {{ distribution.win }}</div>
              <div><span class="legend-dot fallback"></span>兜底 {{ distribution.fallback }}</div>
              <div><span class="legend-dot empty"></span>空广告 {{ distribution.empty }}</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card class="panel" shadow="never">
          <template #header>近 30 分钟请求趋势</template>
          <div v-if="overview?.trend.length" class="trend">
            <div v-for="point in overview.trend" :key="point.minute" class="trend-col">
              <div
                class="trend-bar"
                :style="{ height: `${barHeight(point.bids)}px` }"
                :title="`${point.minute} · ${point.bids} 次 · 兜底 ${point.fallback}`"
              ></div>
              <span class="trend-label">{{ point.minute }}</span>
            </div>
          </div>
          <el-empty v-else description="近 30 分钟暂无竞价数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>最近竞价</span>
          <el-button size="small" @click="recentDrawer = true">查看全部</el-button>
        </div>
      </template>
      <el-table :data="recent.slice(0, 5)" stripe size="small">
        <el-table-column prop="bidAt" label="时间" width="170" />
        <el-table-column label="requestId" width="150">
          <template #default="{ row }">
            <span class="mono">{{ shortId(row.requestId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="adId" label="adId" width="100" />
        <el-table-column label="胜出价" width="110">
          <template #default="{ row }">{{ money(row.winBid) }}</template>
        </el-table-column>
        <el-table-column label="成交价" width="110">
          <template #default="{ row }">{{ money(row.payPrice) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="resultType(row)" size="small">{{ resultLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ row.costMs }} ms</template>
        </el-table-column>
      </el-table>
      <p class="hint">
        明细由本地队列异步落库，可能有约 200ms 延迟。结果中的“兜底”表示这次请求没有可投放广告
        （无候选 / 预算耗尽 / 依赖故障 / 超时），系统返回固定 adId=999999 的保底广告；
        它的出价是配置值，payPrice 为“—”表示不计费。
      </p>
    </el-card>

    <el-drawer v-model="recentDrawer" title="最近竞价" size="760px">
      <el-table :data="recent" stripe size="small">
        <el-table-column prop="bidAt" label="时间" width="170" />
        <el-table-column prop="requestId" label="requestId" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.requestId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="adId" label="adId" width="100" />
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
      </el-table>
    </el-drawer>
  </div>
</template>
