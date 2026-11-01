<script setup lang="ts">
import AdStatusTag from '@/components/AdStatusTag.vue'
import type { AdDetail } from '@/api/types'
import { money } from '@/format'

const visible = defineModel<boolean>({ required: true })
defineProps<{
  detail: AdDetail | null
  loading: boolean
}>()

function dimLabel(dimType: number): string {
  const labels: Record<number, string> = {
    1: '地域',
    2: '年龄段',
    3: '性别',
    4: '兴趣',
    5: '系统',
    6: '设备',
  }
  return labels[dimType] ?? `维度 ${dimType}`
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <el-drawer v-model="visible" title="广告详情" size="440px">
    <div v-loading="loading">
      <template v-if="detail">
        <div class="drawer-title">#{{ detail.ad.id }} · {{ detail.ad.title }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="广告主">{{ detail.ad.advertiserId }}</el-descriptions-item>
          <el-descriptions-item label="类目">{{ detail.ad.categoryCode }}</el-descriptions-item>
          <el-descriptions-item label="出价">{{ money(detail.ad.bid) }}</el-descriptions-item>
          <el-descriptions-item label="日预算">{{ money(detail.ad.dailyBudget) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <AdStatusTag :status="detail.ad.status" />
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(detail.ad.updatedAt) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="drawer-section">定向条件</h4>
        <el-table :data="detail.targets" size="small" stripe>
          <el-table-column label="维度" width="110">
            <template #default="{ row }">{{ dimLabel(row.dimType) }}</template>
          </el-table-column>
          <el-table-column prop="dimValue" label="取值" />
        </el-table>
        <p class="hint">定向条件参与 ad JOIN ad_target 检索；没有定向的广告永远不会被命中。</p>
      </template>
    </div>
  </el-drawer>
</template>
