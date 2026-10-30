<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getBudget, getFrequency } from '@/api/console'
import type { BudgetView, FrequencyView } from '@/api/types'
import { cents, errorText, money, percent, ttlLabel } from '@/format'
import { session } from '@/session'

type TagType = 'success' | 'warning' | 'danger' | 'info'

const budget = ref<BudgetView | null>(null)
const frequency = ref<FrequencyView | null>(null)
const loading = ref(false)
const auto = ref(true)
let timer: ReturnType<typeof setInterval> | undefined

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

const statusLabel = computed(() => {
  switch (budget.value?.status) {
    case 1:
      return '在投'
    case 2:
      return '暂停'
    case 3:
      return '已耗尽'
    default:
      return '未知'
  }
})

const statusType = computed<TagType>(() => {
  switch (budget.value?.status) {
    case 1:
      return 'success'
    case 2:
      return 'warning'
    case 3:
      return 'danger'
    default:
      return 'info'
  }
})

async function refresh(): Promise<void> {
  if (!session.adId) return
  loading.value = true
  try {
    const [budgetView, frequencyView] = await Promise.all([
      getBudget(session.adId),
      getFrequency(session.adId, session.userId),
    ])
    budget.value = budgetView
    frequency.value = frequencyView
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
    timer = setInterval(refresh, 2000)
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
        <h1>预算与频次</h1>
        <p>直接读取 Redis 的当日预算与当前小时频次；只读查询，不会播种或修改任何 key。</p>
      </div>
      <el-tag type="info" effect="plain">GET /console-api/v1/budget · /frequency</el-tag>
    </header>

    <el-card class="panel" shadow="never">
      <div class="filters">
        <el-input-number
          v-model="session.adId"
          :min="1"
          controls-position="right"
          @change="refresh"
        />
        <el-input
          v-model="session.userId"
          placeholder="userId（可空，匿名不控频）"
          clearable
          @change="refresh"
        />
        <el-button :loading="loading" @click="refresh">刷新</el-button>
        <el-switch v-model="auto" active-text="自动刷新" />
        <span class="hint">竞价后会自动带入最近一次胜出广告与 userId。</span>
      </div>
    </el-card>

    <div v-if="budget" class="metric-grid six">
      <div class="metric">
        <span>广告</span>
        <strong>#{{ budget.adId }}</strong>
        <small>{{ budget.title }}</small>
      </div>
      <div class="metric">
        <span>状态</span>
        <strong><el-tag :type="statusType">{{ statusLabel }}</el-tag></strong>
      </div>
      <div class="metric">
        <span>日预算</span>
        <strong>{{ money(budget.dailyBudget) }}</strong>
      </div>
      <div class="metric">
        <span>剩余额度</span>
        <strong>{{ cents(budget.remainingCents) }}</strong>
      </div>
      <div class="metric">
        <span>已消耗</span>
        <strong>{{ cents(budget.spentCents) }}</strong>
      </div>
      <div class="metric">
        <span>预算 key TTL</span>
        <strong>{{ ttlLabel(budget.ttlSeconds) }}</strong>
      </div>
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
          <div class="metric">
            <span>用户</span>
            <strong class="mono">{{ frequency.userId }}</strong>
            <small>adId #{{ frequency.adId }}</small>
          </div>
          <div class="metric">
            <span>本小时成交次数</span>
            <strong>{{ frequency.count ?? 0 }} / {{ frequency.maxPerHour }}</strong>
            <small>上限来自 bidpulse.frequency.max-per-hour</small>
          </div>
          <div class="metric">
            <span>频次 key TTL</span>
            <strong>{{ ttlLabel(frequency.ttlSeconds) }}</strong>
            <small>freq:u:{userId}:{yyyyMMddHH}</small>
          </div>
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
