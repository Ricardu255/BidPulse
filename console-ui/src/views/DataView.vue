<script setup lang="ts">
import FilterBar from '@/components/FilterBar.vue'
import PageHeader from '@/components/PageHeader.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '@/api/console'
import type { DataView } from '@/api/types'
import { errorText, money } from '@/format'
import { toCsv } from '@/csv'
import { FALLBACK_AD_ID } from '@/constants'

const tableMeta: Record<string, {
  label: string
  description: string
  columns: Record<string, string>
}> = {
  bid_log: {
    label: '竞价明细',
    description: '每次竞价请求的结果：候选、成交价与耗时。明细异步落库，可能有约 200ms 延迟。',
    columns: {
      request_id: '请求 ID',
      ad_id: '广告 ID',
      win_bid: '胜出价（元）',
      pay_price: '成交价（元）',
      has_ad: '是否有广告',
      cost_ms: '耗时（毫秒）',
      bid_at: '竞价时间',
    },
  },
  billing_event: {
    label: '计费流水',
    description: '每次成交产生的扣费事实；由 Kafka 异步写入，是预算重建与对账的依据。',
    columns: {
      event_id: '计费事件 ID',
      ad_id: '广告 ID',
      advertiser_id: '广告主 ID',
      pay_price: '扣费金额（元）',
      ts: '事件时间戳（毫秒）',
      event_time: '事件时间',
    },
  },
  bid_stat_hourly: {
    label: '小时聚合',
    description: '按自然小时汇总的曝光与消耗，供趋势判断和运营查看。',
    columns: {
      stat_hour: '统计小时',
      ad_id: '广告 ID',
      impressions: '曝光次数',
      spend: '消耗（元）',
    },
  },
  ad_freq: {
    label: '广告曝光频次',
    description: '按广告累计的曝光次数，用于反作弊与频次观察。',
    columns: { ad_id: '广告 ID', cnt: '曝光次数' },
  },
  processed_event: {
    label: '消费去重',
    description: 'Kafka 消费组的幂等记录；同一条事件在同一消费组只处理一次。',
    columns: {
      event_id: '事件 ID',
      grp: '消费组',
      created_at: '首次处理时间',
    },
  },
}

const tables = Object.entries(tableMeta).map(([value, meta]) => ({ value, label: meta.label }))
const currentMeta = computed(() => tableMeta[data.value?.table ?? table.value] ?? tableMeta.bid_log)
const displayColumns = computed(() =>
  (data.value?.columns ?? []).filter((column) => !(data.value?.table === 'billing_event' && column === 'ts')),
)
const MONETARY_COLUMNS = new Set(['win_bid', 'pay_price', 'spend'])
const GROUP_LABELS: Record<string, string> = {
  'billing-recon': '计费账单',
  'hourly-preagg': '小时聚合',
  'freq-antifraud': '频次反作弊',
}

const table = ref('bid_log')
const limit = ref(20)
const adId = ref<number | undefined>(undefined)
const requestId = ref('')
const data = ref<DataView | null>(null)
const loading = ref(false)
const error = ref('')

function formatValue(column: string, value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  if (column === 'has_ad') return Number(value) === 1 ? '有广告' : '空广告'
  if (column === 'ad_id' && Number(value) === FALLBACK_AD_ID) return `兜底广告（${FALLBACK_AD_ID}）`
  if (column === 'grp') return GROUP_LABELS[String(value)] ?? String(value)
  if (MONETARY_COLUMNS.has(column)) return money(Number(value))
  if (column === 'created_at' || column === 'event_time') return String(value).replace('T', ' ').slice(0, 19)
  return String(value)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await getData(table.value, {
      limit: limit.value,
      adId: adId.value,
      requestId: requestId.value.trim() || undefined,
    })
  } catch (e) {
    error.value = errorText(e)
    data.value = null
    ElMessage({ message: error.value, type: 'error', grouping: true })
  } finally {
    loading.value = false
  }
}

function isNumericColumn(column: string): boolean {
  const sample = data.value?.rows.find((row) => row[column] != null)?.[column]
  if (typeof sample === 'number') return true
  return typeof sample === 'string' && /^-?\d+(\.\d+)?$/.test(sample)
}

function exportCsv(): void {
  if (!data.value) return
  // BOM 让 Excel 正确识别 UTF-8 中文
  const blob = new Blob([toCsv(data.value.columns, data.value.rows)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${data.value.table}-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
  anchor.click()
  // 部分浏览器在 click 后立刻 revoke 会取消下载
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader
      title="数据查询"
      description="按主题查看竞价、计费与聚合数据；只读查询，支持导出 CSV。"
      tag="只读查询"
    />

    <el-card class="panel" shadow="never">
      <FilterBar>
        <el-select v-model="table" style="width: 230px" @change="load">
          <el-option v-for="item in tables" :key="item.value" :value="item.value" :label="item.label" />
        </el-select>
        <span class="filter-label">查询行数</span>
        <el-input-number v-model="limit" :min="1" :max="200" :precision="0" :step="10" controls-position="right" />
        <template v-if="table !== 'processed_event'">
          <span class="filter-label">广告 ID</span>
          <el-input-number v-model="adId" :min="1" placeholder="广告 ID（可空）" controls-position="right" />
        </template>
        <el-input
          v-if="table === 'bid_log'"
          v-model="requestId"
          placeholder="请求 ID（可空）"
          clearable
          style="width: 210px"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button :disabled="!data?.rows.length" @click="exportCsv">导出当前结果 CSV</el-button>
        <el-button v-if="error" @click="load">重试</el-button>
      </FilterBar>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <div>
            <span>{{ currentMeta.label }}</span>
            <p class="table-description">{{ currentMeta.description }}</p>
          </div>
          <span class="hint">{{ data?.rows.length ?? 0 }} 行 · 上限 200</span>
        </div>
      </template>
      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="formula" />
      <el-table v-if="!error" v-loading="loading" :data="data?.rows ?? []" size="small" max-height="560">
        <el-table-column
          v-for="column in displayColumns"
          :key="column"
          :label="currentMeta.columns[column] ?? column"
          :min-width="column === 'request_id' ? 220 : column === 'bid_at' ? 180 : 140"
          :align="isNumericColumn(column) ? 'right' : 'left'"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="mono">{{ formatValue(column, row[column]) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !error && !(data?.rows.length)" description="当前条件下没有数据" :image-size="60" />
      <p class="hint">数据浏览只读；竞价明细由本地队列异步落库，计费流水由 Kafka 消费异步写入。</p>
    </el-card>
  </div>
</template>
