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

function formatValue(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await getData(table.value, {
      limit: limit.value,
      adId: adId.value,
      requestId: requestId.value.trim() || undefined,
    })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function exportCsv(): void {
  if (!data.value) return
  const escape = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`
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
  URL.revokeObjectURL(url)
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
        <el-input-number v-model="limit" :min="1" :max="200" controls-position="right" />
        <el-input-number v-model="adId" :min="1" placeholder="adId（可空）" controls-position="right" />
        <el-input
          v-if="table === 'bid_log'"
          v-model="requestId"
          placeholder="requestId（可空）"
          clearable
          style="width: 210px"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button :disabled="!data?.rows.length" @click="exportCsv">导出 CSV</el-button>
      </div>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>{{ data?.table ?? table }}</span>
          <span class="hint">{{ data?.rows.length ?? 0 }} 行 · 上限 200</span>
        </div>
      </template>
      <el-table v-loading="loading" :data="data?.rows ?? []" stripe size="small" max-height="560">
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
      <el-empty v-if="!loading && !(data?.rows.length)" description="当前条件下没有数据" :image-size="60" />
      <p class="hint">数据浏览只读；bid_log 由本地队列异步落库，billing_event 由 Kafka 消费异步写入。</p>
    </el-card>
  </div>
</template>
