<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import FilterBar from '@/components/FilterBar.vue'
import { getDltMessages, replayDlt, replayDltOne } from '@/api/console'
import type { DltMessageView, DltReplayStats } from '@/api/types'
import { errorText } from '@/format'
import { hasRole } from '@/auth'

const canWrite = hasRole('OPERATOR', 'ADMIN')
const dltMessages = ref<DltMessageView[]>([])
const dltLoading = ref(false)
const dltLimit = ref(20)
const replayMax = ref(100)
const replaying = ref(false)
const replayingOne = ref<string | null>(null)
const lastReplay = ref<DltReplayStats | null>(null)

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

function preview(value: string | null): string {
  if (!value) return '—'
  return value.length > 90 ? `${value.slice(0, 90)}…` : value
}

function outcomeLabel(outcome: string): string {
  if (outcome === 'REPLAYED') return '已投递主主题'
  if (outcome === 'SKIPPED_INVALID') return '已跳过：报文不可解析或缺少 eventId'
  if (outcome === 'SKIPPED_EXHAUSTED') return '已跳过：达到最大重放次数'
  return outcome
}

async function refreshDlt(): Promise<void> {
  dltLoading.value = true
  try {
    dltMessages.value = await getDltMessages(dltLimit.value)
  } catch (e) {
    notifyError(e)
  } finally {
    dltLoading.value = false
  }
}

async function triggerReplay(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将 DLT 中可解析、未超过 3 次的消息重放回主主题，本轮上限 ${replayMax.value} 条。继续？`,
      '触发 DLT 重放',
      { type: 'warning', confirmButtonText: '开始重放', cancelButtonText: '取消' },
    )
    replaying.value = true
    lastReplay.value = await replayDlt(replayMax.value)
    ElMessage.success(
      `重放完成：成功 ${lastReplay.value.replayed} 条，跳过不可解析 ${lastReplay.value.skippedInvalid} 条，超限 ${lastReplay.value.skippedExhausted} 条`,
    )
    await refreshDlt()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    notifyError(e)
  } finally {
    replaying.value = false
  }
}

async function replayOne(row: DltMessageView): Promise<void> {
  const key = `${row.partition}:${row.offset}`
  try {
    await ElMessageBox.confirm(
      `重放 partition=${row.partition} offset=${row.offset}？DLT 原记录会保留，重复消费由 processed_event 幂等吸收。`,
      '单条重放',
      { type: 'warning', confirmButtonText: '重放', cancelButtonText: '取消' },
    )
    replayingOne.value = key
    const result = await replayDltOne(row.partition, row.offset)
    ElMessage.success(`单条重放结果：${outcomeLabel(result.outcome)}`)
    await refreshDlt()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    notifyError(e)
  } finally {
    replayingOne.value = null
  }
}

onMounted(refreshDlt)
</script>

<template>
  <el-card class="panel" shadow="never">
    <FilterBar>
      <span class="hint">最近 DLT 消息（含已处理/已跳过）</span>
      <span class="filter-label">拉取条数</span>
      <el-input-number v-model="dltLimit" :min="1" :max="50" :precision="0" size="small" />
      <el-button size="small" :loading="dltLoading" @click="refreshDlt">刷新</el-button>
      <span class="filter-label">批量重放上限</span>
      <el-input-number v-model="replayMax" :min="1" :max="1000" :precision="0" size="small" />
      <el-button size="small" type="warning" plain :disabled="!canWrite" :loading="replaying" @click="triggerReplay">
        批量重放
      </el-button>
    </FilterBar>
  </el-card>

  <el-alert
    v-if="lastReplay"
    type="success"
    :closable="false"
    show-icon
    :title="`上轮结果：重放 ${lastReplay.replayed} 条，跳过不可解析 ${lastReplay.skippedInvalid} 条，超限 ${lastReplay.skippedExhausted} 条`"
    class="formula"
  />

  <el-card class="panel" shadow="never">
    <el-table :data="dltMessages" v-loading="dltLoading" stripe size="small">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="dlt-detail">
            <div class="dlt-label">value</div>
            <pre class="dlt-value">{{ row.value }}</pre>
            <div class="dlt-label">headers</div>
            <pre class="dlt-value">{{ JSON.stringify(row.headers, null, 2) }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="partition" label="分区" width="80" />
      <el-table-column prop="offset" label="位点 offset" width="110" />
      <el-table-column prop="replayCount" label="重放次数" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.processed ? 'info' : 'warning'" size="small">
            {{ row.processed ? '已处理' : '待处理' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="key" label="消息 key" width="120" />
      <el-table-column prop="timestamp" label="时间" width="170" />
      <el-table-column label="value 预览" min-width="220">
        <template #default="{ row }">{{ preview(row.value) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="warning"
            :disabled="!canWrite"
            :loading="replayingOne === `${row.partition}:${row.offset}`"
            @click="replayOne(row)"
          >
            重放
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!dltMessages.length" description="DLT 暂无待处理消息" :image-size="60" />
    <p class="hint">
      浏览使用独立 assign 消费者，不提交位点；单条重放只投递主主题，不推进定时任务位点。
    </p>
  </el-card>
</template>
