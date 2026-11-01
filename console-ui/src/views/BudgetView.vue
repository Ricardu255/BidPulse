<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import AdStatusTag from '@/components/AdStatusTag.vue'
import MetricCard from '@/components/MetricCard.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getBudget, getFrequency } from '@/api/console'
import { ApiError } from '@/api/client'
import type { BudgetView, FrequencyView } from '@/api/types'
import { cents, errorText, money, percent, ttlLabel } from '@/format'
import { session } from '@/session'
import { usePolling } from '@/composables/usePolling'

const router = useRouter()
const budget = ref<BudgetView | null>(null)
const frequency = ref<FrequencyView | null>(null)
const loading = ref(false)
const notFound = ref(false)
const auto = ref(true)

const budgetPercent = computed(() => {
  if (budget.value?.remainingCents == null || !budget.value.dailyBudget) return 0
  return percent(budget.value.remainingCents, Number(budget.value.dailyBudget) * 100)
})

const frequencyPercent = computed(() => {
  if (!frequency.value?.enabled) return 0
  return percent(frequency.value.count, frequency.value.maxPerHour)
})

const budgetProgressStatus = computed<'success' | 'exception'>(() =>
  budgetPercent.value < 20 ? 'exception' : 'success',
)

const frequencyProgressStatus = computed<'success' | 'exception'>(() =>
  frequencyPercent.value >= 100 ? 'exception' : 'success',
)

let refreshSeq = 0

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

function isAdMissing(e: unknown): boolean {
  return e instanceof ApiError && e.status === 404 && /广告不存在/.test(e.message)
}

async function refresh(force = false): Promise<void> {
  if (!session.adId) {
    budget.value = null
    frequency.value = null
    notFound.value = false
    return
  }
  if (!force && notFound.value) return

  const adId = session.adId
  const seq = ++refreshSeq
  loading.value = true
  try {
    const [budgetResult, frequencyResult] = await Promise.allSettled([
      getBudget(adId),
      getFrequency(adId, session.userId),
    ])
    // 请求期间用户改了 adId：丢弃旧响应，避免旧广告数据覆盖新广告。
    if (seq !== refreshSeq || adId !== session.adId) return

    if (budgetResult.status === 'rejected') {
      const e = budgetResult.reason
      if (isAdMissing(e)) {
        notFound.value = true
        budget.value = null
        frequency.value = null
        return
      }
      notifyError(e)
      return
    }

    budget.value = budgetResult.value
    notFound.value = false
    if (frequencyResult.status === 'fulfilled') {
      frequency.value = frequencyResult.value
    } else {
      // 频次接口不可用（例如旧后端缺少 /frequency）时，仍保留预算展示。
      frequency.value = null
      if (!isAdMissing(frequencyResult.reason)) notifyError(frequencyResult.reason)
    }
  } finally {
    if (seq === refreshSeq) loading.value = false
  }
}

function onFilterChange(): void {
  notFound.value = false
  refresh(true)
}

function useDefaultAd(): void {
  session.adId = 110
  localStorage.setItem('bidpulse.console.adId', '110')
  onFilterChange()
}

function openAds(): void {
  router.push('/ads')
}

usePolling(refresh, 2000, auto)

onMounted(refresh)
</script>

<template>
  <div class="page">
    <PageHeader
      title="预算与频次"
      description="查某个广告今天还剩多少钱、某个用户今天已经看过几次；只读，不会修改预算。"
      tag="GET /console-api/v1/budget · /frequency"
    />

    <el-card class="panel" shadow="never">
      <div class="filters">
        <span class="filter-label">广告 ID</span>
        <el-input-number
          v-model="session.adId"
          :min="1"
          :precision="0"
          :step="1"
          controls-position="right"
          @change="onFilterChange"
        />
        <span class="filter-label">用户 ID</span>
        <el-input
          v-model="session.userId"
          placeholder="userId（可空，匿名不控频）"
          clearable
          @change="onFilterChange"
        />
        <el-button :loading="loading" @click="refresh(true)">刷新</el-button>
        <el-switch v-model="auto" active-text="自动刷新" />
        <span class="hint">竞价后会自动带入最近一次胜出广告与 userId。</span>
      </div>
    </el-card>

    <el-alert v-if="notFound" type="warning" :closable="false" show-icon class="panel">
      <template #title>没有找到这个广告</template>
      <div class="not-found">
        <p>adId 必须存在于 ad 表；兜底广告 999999 不在表中，已删除的广告也查不到。</p>
        <el-button size="small" type="primary" @click="useDefaultAd">使用广告 110</el-button>
        <el-button size="small" @click="openAds">打开广告列表</el-button>
      </div>
    </el-alert>

    <div v-if="budget" class="metric-grid six">
      <MetricCard label="广告" :value="`#${budget.adId}`" :hint="budget.title" />
      <MetricCard label="状态">
        <template #value><AdStatusTag :status="budget.status" /></template>
      </MetricCard>
      <MetricCard label="日预算" :value="money(budget.dailyBudget)" />
      <MetricCard label="剩余额度" :value="cents(budget.remainingCents)" />
      <MetricCard label="已消耗" :value="cents(budget.spentCents)" />
      <MetricCard label="预算 key TTL" :value="ttlLabel(budget.ttlSeconds)" />
    </div>

    <el-card v-if="budget" class="panel" shadow="never">
      <template #header>当日预算</template>
      <el-alert
        v-if="!budget.seeded && budget.spentCents == null"
        type="warning"
        :closable="false"
        show-icon
        title="Redis 预算 key 尚未初始化：下一次竞价会按账单惰性播种。"
      />
      <el-alert
        v-else-if="!budget.seeded"
        type="info"
        :closable="false"
        show-icon
        title="budget key 缺失但 spent 存在：当前展示的是「日预算 − 已消耗」反推值。"
      />
      <div v-else class="progress-block">
        <div class="progress-caption">
          <span>剩余 {{ cents(budget.remainingCents) }} / 日预算 {{ money(budget.dailyBudget) }}</span>
          <span>{{ budgetPercent.toFixed(1) }}%</span>
        </div>
        <el-progress
          :percentage="budgetPercent"
          :stroke-width="12"
          :show-text="false"
          :status="budgetProgressStatus"
        />
        <p class="hint">
          预算 key 为 budget:{adId}:{yyyyMMdd}，spent 为累计消耗；金额在 Redis 内以「分」存储。
        </p>
      </div>
    </el-card>

    <el-card v-if="frequency" class="panel" shadow="never">
      <template #header>实时频次</template>
      <el-alert
        v-if="!frequency.enabled"
        type="info"
        :closable="false"
        show-icon
        title="匿名流量或频次开关关闭：本次不控频，不读写频次 key。"
      />
      <template v-else>
        <div class="metric-grid three">
          <MetricCard
            label="用户"
            :hint="`adId #${frequency.adId}`"
          >
            <template #value><strong class="mono">{{ frequency.userId }}</strong></template>
          </MetricCard>
          <MetricCard
            label="本小时成交次数"
            :value="`${frequency.count ?? 0} / ${frequency.maxPerHour}`"
            hint="上限来自 bidpulse.frequency.max-per-hour"
          />
          <MetricCard
            label="频次 key TTL"
            :value="ttlLabel(frequency.ttlSeconds)"
            hint="freq:u:{userId}:{yyyyMMddHH}"
          />
        </div>
        <div class="progress-block">
          <div class="progress-caption">
            <span>频次使用率</span>
            <span>{{ frequencyPercent.toFixed(1) }}%</span>
          </div>
          <el-progress
            :percentage="frequencyPercent"
            :stroke-width="12"
            :show-text="false"
            :status="frequencyProgressStatus"
          />
          <p class="hint">超限后该候选会被跳过，不扣预算、不计数，也不会把广告标成已耗尽。</p>
        </div>
      </template>
    </el-card>
  </div>
</template>
