<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getOverview, getRecentBids } from '@/api/console'
import type { OverviewView, RecentBidView } from '@/api/types'
import { errorText, money } from '@/format'

const overview = ref<OverviewView | null>(null)
const recent = ref<RecentBidView[]>([])
const loading = ref(false)
const auto = ref(true)
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
  }
  return labels[key] ?? key
}

function healthType(value: string): 'success' | 'danger' | 'info' {
  if (value === 'UP') return 'success'
  if (value === 'DOWN' || value === 'OUT_OF_SERVICE') return 'danger'
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
        <h1>概览</h1>
        <p>今日竞价、兜底率、耗时、消耗与广告状态；下方是近 30 分钟趋势和最近竞价。</p>
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

    <el-row :gutter="16">
      <el-col :xs="24" :md="9">
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

      <el-col :xs="24" :md="15">
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
      <template #header>最近竞价</template>
      <el-table :data="recent" stripe size="small">
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
      <p class="hint">明细由本地队列异步落库，可能有约 200ms 延迟；兜底广告 adId 固定为 999999。</p>
    </el-card>
  </div>
</template>
