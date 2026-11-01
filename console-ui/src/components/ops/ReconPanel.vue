<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import FilterBar from '@/components/FilterBar.vue'
import MetricCard from '@/components/MetricCard.vue'
import { getReconReport, runRecon } from '@/api/console'
import type { ReconReport } from '@/api/types'
import { cents, errorText } from '@/format'

const recon = ref<ReconReport | null>(null)
const reconLoading = ref(false)
const reconRunning = ref(false)

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

function reconStatusLabel(status: string | undefined): string {
  if (status === 'OK') return '正常'
  if (status === 'FAILED') return '失败'
  return status || '未知'
}

function directionLabel(direction: string): string {
  if (direction === 'REDIS_AHEAD') return 'Redis 多记'
  if (direction === 'DB_AHEAD') return 'Redis 少记'
  return direction
}

function actionLabel(action: string): string {
  if (action === 'REPAIRED') return '已修复'
  if (action === 'ALERT') return '已告警'
  if (action === 'SKIPPED') return '已跳过'
  return action
}

function reconType(status: string | undefined): 'success' | 'danger' | 'info' {
  if (status === 'OK') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function directionType(direction: string): 'warning' | 'danger' | 'info' {
  if (direction === 'REDIS_AHEAD') return 'warning'
  if (direction === 'DB_AHEAD') return 'danger'
  return 'info'
}

function actionType(action: string): 'success' | 'warning' | 'danger' {
  if (action === 'REPAIRED') return 'success'
  if (action === 'ALERT') return 'warning'
  return 'danger'
}

async function refreshRecon(): Promise<void> {
  reconLoading.value = true
  try {
    recon.value = await getReconReport()
  } catch (e) {
    notifyError(e)
  } finally {
    reconLoading.value = false
  }
}

async function triggerRecon(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '立即对比 Redis spent 与 billing_event 当日汇总：Redis 低于账单自动修复，高于账单只告警。继续？',
      '立即对账',
      { type: 'warning', confirmButtonText: '开始对账', cancelButtonText: '取消' },
    )
    reconRunning.value = true
    recon.value = await runRecon()
    ElMessage.success('对账完成')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    await refreshRecon()
    notifyError(e)
  } finally {
    reconRunning.value = false
  }
}

onMounted(refreshRecon)
</script>

<template>
  <el-card class="panel" shadow="never">
    <FilterBar>
      <el-button size="small" :loading="reconLoading" @click="refreshRecon">刷新</el-button>
      <el-button size="small" type="primary" :loading="reconRunning" @click="triggerRecon">
        立即对账
      </el-button>
      <span class="hint">Redis 低于账单自动修复；高于账单只告警。</span>
    </FilterBar>
  </el-card>

  <template v-if="recon">
    <div class="metric-grid">
      <MetricCard label="最近执行">
        <template #value><strong class="recon-time">{{ recon.lastRunAt }}</strong></template>
        <template #hint>
          <small>
            状态
            <el-tag :type="reconType(recon.status)" size="small">
              {{ reconStatusLabel(recon.status) }}
            </el-tag>
          </small>
        </template>
      </MetricCard>
      <MetricCard label="对账广告数" :value="recon.adCount" />
      <MetricCard label="Redis 高于账单" :value="recon.alerted" hint="只告警，不调低 spent" />
      <MetricCard label="按账单修复" :value="recon.repaired" hint="DB 高于 Redis 时" />
      <MetricCard label="耗时" :value="`${recon.durationMs} ms`" />
    </div>

    <el-alert
      v-if="recon.status === 'FAILED'"
      type="error"
      :closable="false"
      show-icon
      :title="recon.message || '对账失败，请查看后端日志'"
      class="formula"
    />

    <el-card class="panel" shadow="never">
      <template #header>差异明细</template>
      <el-table :data="recon.mismatches ?? []" stripe size="small" max-height="360">
        <el-table-column prop="adId" label="adId" width="100" />
        <el-table-column label="方向" width="130">
          <template #default="{ row }">
            <el-tag :type="directionType(row.direction)" size="small">{{ directionLabel(row.direction) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Redis" width="120">
          <template #default="{ row }">{{ cents(row.redisCents) }}</template>
        </el-table-column>
        <el-table-column label="账单" width="120">
          <template #default="{ row }">{{ cents(row.dbCents) }}</template>
        </el-table-column>
        <el-table-column label="差值" width="120">
          <template #default="{ row }">{{ cents(row.diffCents) }}</template>
        </el-table-column>
        <el-table-column label="处理" width="120">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" size="small">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!(recon.mismatches?.length)"
        description="本次对账没有超出容差的差异"
        :image-size="60"
      />
    </el-card>
  </template>
  <el-empty v-else description="尚未执行过对账" :image-size="60" />
</template>
