<script setup lang="ts">
import { computed } from 'vue'
import type { BidTrace } from '@/api/types'
import { money } from '@/format'

const props = defineProps<{ trace: BidTrace }>()

type TagType = 'success' | 'warning' | 'danger' | 'info'

const REASON_LABELS: Record<string, string> = {
  FORCE: '强制兜底开关已打开',
  DB_UNAVAILABLE: '候选检索失败（数据库不可用或熔断）',
  NO_CANDIDATE: '没有命中候选',
  DEADLINE_EXCEEDED: '超出总超时预算',
  REDIS_UNAVAILABLE: 'Redis 计费不可用或熔断',
  BUDGET_SEED_FAILED: '预算冷启动重建失败',
  BUDGET_EXHAUSTED: '候选预算全部耗尽',
  FREQ_CAPPED: '候选全部被频次拦截',
  BUDGET_AND_FREQ_EXHAUSTED: '预算耗尽且频次拦截',
  NO_PAYABLE_CANDIDATE: '没有可扣减预算的候选',
}

const CANDIDATE_LABELS: Record<string, string> = {
  SUCCESS: '成交',
  INSUFFICIENT: '预算不足',
  FREQ_CAPPED: '频次超限',
  NEEDS_SEED: '待重建预算',
  REDIS_ERROR: 'Redis 异常',
}

const outcomeLabel = computed(() => {
  if (props.trace.outcome === 'WIN') return '成交'
  if (props.trace.outcome === 'FALLBACK') return '兜底'
  return '空广告'
})

const outcomeType = computed<TagType>(() => {
  if (props.trace.outcome === 'WIN') return 'success'
  if (props.trace.outcome === 'FALLBACK') return 'warning'
  return 'info'
})

const reasonLabel = computed(() => {
  const reason = props.trace.fallbackReason
  if (!reason) return ''
  return REASON_LABELS[reason] ?? reason
})

const candidates = computed(() => props.trace.candidates ?? [])

function candidateLabel(result: string): string {
  return CANDIDATE_LABELS[result] ?? result
}

function candidateType(result: string): TagType {
  if (result === 'SUCCESS') return 'success'
  if (result === 'FREQ_CAPPED') return 'warning'
  if (result === 'REDIS_ERROR') return 'danger'
  return 'info'
}
</script>

<template>
  <div class="trace">
    <div class="trace-head">
      <el-tag :type="outcomeType" size="large">{{ outcomeLabel }}</el-tag>
      <span v-if="reasonLabel" class="trace-reason">{{ reasonLabel }}</span>
      <span class="trace-timings">
        检索 {{ trace.timings.dbMs }} ms · 计费 {{ trace.timings.redisMs }} ms · 总计
        {{ trace.timings.totalMs }} ms
      </span>
    </div>

    <div class="trace-meta">
      候选 {{ trace.candidateCnt }} 条 · 匹配 {{ trace.matchCnt }} 条
      <template v-if="trace.winnerAdId != null"> · 胜出广告 #{{ trace.winnerAdId }}</template>
      <template v-if="trace.payPrice != null"> · 成交价 {{ money(trace.payPrice) }}</template>
    </div>

    <el-timeline class="trace-timeline">
      <el-timeline-item
        v-for="(step, index) in trace.steps"
        :key="`${step.stage}-${index}`"
        :timestamp="step.costMs > 0 ? `${step.costMs} ms` : ''"
        placement="top"
      >
        <strong>{{ step.stage }}</strong>
        <span class="trace-step-detail">{{ step.detail }}</span>
      </el-timeline-item>
    </el-timeline>

    <el-table :data="candidates" size="small" stripe>
      <el-table-column prop="adId" label="广告 ID" width="110" />
      <el-table-column label="出价" width="120" align="right">
        <template #default="{ row }">{{ money(row.bid) }}</template>
      </el-table-column>
      <el-table-column label="本次成交价" width="130" align="right">
        <template #default="{ row }">{{ money(row.price) }}</template>
      </el-table-column>
      <el-table-column label="裁决" width="130">
        <template #default="{ row }">
          <el-tag :type="candidateType(row.result)" size="small">
            {{ candidateLabel(row.result) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <p class="trace-hint">
      轨迹只在控制台发起的竞价里采集：候选最多保留 10 条，只含广告 ID、出价、成交价与裁决结果，
      不含用户标识；随竞价明细异步落库，可丢可采样。
    </p>
  </div>
</template>

<style scoped>
.trace-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.trace-reason {
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
  font-weight: 500;
}

.trace-timings {
  margin-left: auto;
  color: var(--bp-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.trace-meta {
  margin-bottom: 16px;
  color: var(--bp-muted);
  font-size: 12px;
}

.trace-timeline {
  margin: 8px 0 16px;
  padding-left: 2px;
}

.trace-step-detail {
  margin-left: 8px;
  color: var(--bp-muted);
  font-size: 13px;
}

.trace-hint {
  margin: 12px 0 0;
  color: var(--bp-muted);
  font-size: 12px;
  line-height: 1.55;
}
</style>
