<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '@/api/console'
import type { DataView } from '@/api/types'
import { errorText } from '@/format'

const tables = [
  { value: 'bid_log', label: '竞价明细 bid_log' },
  { value: 'billing_event', label: '计费流水 billing_event' },
  { value: 'bid_stat_hourly', label: '小时聚合 bid_stat_hourly' },
  { value: 'ad_freq', label: '广告频次 ad_freq' },
  { value: 'processed_event', label: '消费去重 processed_event' },
]

const table = ref('bid_log')
const limit = ref(20)
const adId = ref<number | undefined>(undefined)
const requestId = ref('')
const data = ref<DataView | null>(null)
const loading = ref(false)
const error = ref('')

function formatValue(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
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

function exportCsv(): void {
  if (!data.value) return
  const escape = (value: unknown) => {
    let text = String(value ?? '')
    // 防止 CSV 公式注入：以 = + - @ 或控制字符开头的单元格加前缀单引号
    if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
    return `"${text.replace(/"/g, '""')}"`
  }
  const lines = [
    data.value.columns.map(escape).join(','),
    ...data.value.rows.map((row) => data.value!.columns.map((column) => escape(row[column])).join(',')),
  ]
  // BOM 让 Excel 正确识别 UTF-8 中文
  const blob = new Blob([`﻿${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
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
    <header class="page-header">
      <div>
        <h1>数据查询</h1>
        <p>排查问题时直接看底层表；只读查询，支持导出 CSV。</p>
      </div>
      <el-tag type="info" effect="plain">GET /console-api/v1/data/{table}</el-tag>
    </header>

    <el-card class="panel" shadow="never">
      <div class="filters">
        <el-select v-model="table" style="width: 230px" @change="load">
          <el-option v-for="item in tables" :key="item.value" :value="item.value" :label="item.label" />
        </el-select>
        <span class="filter-label">查询行数</span>
        <el-input-number v-model="limit" :min="1" :max="200" :precision="0" :step="10" controls-position="right" />
        <template v-if="table !== 'processed_event'">
          <span class="filter-label">广告 ID</span>
          <el-input-number v-model="adId" :min="1" placeholder="adId（可空）" controls-position="right" />
        </template>
        <el-input
          v-if="table === 'bid_log'"
          v-model="requestId"
          placeholder="requestId（可空）"
          clearable
          style="width: 210px"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button :disabled="!data?.rows.length" @click="exportCsv">导出当前结果 CSV</el-button>
        <el-button v-if="error" @click="load">重试</el-button>
      </div>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>{{ data?.table ?? table }}</span>
          <span class="hint">{{ data?.rows.length ?? 0 }} 行 · 上限 200</span>
        </div>
      </template>
      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="formula" />
      <el-table v-if="!error" v-loading="loading" :data="data?.rows ?? []" stripe size="small" max-height="560">
        <el-table-column
          v-for="column in data?.columns ?? []"
          :key="column"
          :label="column"
          min-width="140"
        >
          <template #default="{ row }">
            <span class="mono">{{ formatValue(row[column]) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !error && !(data?.rows.length)" description="当前条件下没有数据" :image-size="60" />
      <p class="hint">数据浏览只读；bid_log 由本地队列异步落库，billing_event 由 Kafka 消费异步写入。</p>
    </el-card>
  </div>
</template>
