<script setup lang="ts">
import { computed } from 'vue'
import type { TrendPoint } from '@/api/types'

const props = defineProps<{ trend: TrendPoint[] }>()

const buckets = computed(() => {
  const byMinute = new Map(props.trend.map((point) => [point.minute, point]))
  const now = new Date()
  now.setSeconds(0, 0)
  const result: Array<TrendPoint & { zero: boolean }> = []
  for (let i = 29; i >= 0; i -= 1) {
    const time = new Date(now.getTime() - i * 60_000)
    const minute = `${String(time.getHours()).padStart(2, '0')}:${String(time.getMinutes()).padStart(2, '0')}`
    const point = byMinute.get(minute)
    result.push({
      minute,
      bids: point?.bids ?? 0,
      fallback: point?.fallback ?? 0,
      zero: !point || point.bids === 0,
    })
  }
  return result
})

const totalBids = computed(() => buckets.value.reduce((sum, point) => sum + point.bids, 0))
const maxBids = computed(() => Math.max(1, ...buckets.value.map((point) => point.bids)))

function barHeight(bids: number): number {
  if (bids === 0) return 2
  return Math.max(4, Math.round((bids / maxBids.value) * 110))
}
</script>

<template>
  <el-card class="panel" shadow="never">
    <template #header>
      <div class="card-head">
        <span>近 30 分钟请求趋势</span>
        <span class="hint">{{ totalBids }} 次请求</span>
      </div>
    </template>
    <div v-if="totalBids > 0" class="trend">
      <div v-for="(point, index) in buckets" :key="point.minute" class="trend-col">
        <div
          class="trend-bar"
          :class="{ zero: point.zero }"
          :style="{ height: `${barHeight(point.bids)}px` }"
          :title="`${point.minute} · ${point.bids} 次 · 兜底 ${point.fallback}`"
        ></div>
        <span v-if="index % 10 === 0 || index === buckets.length - 1" class="trend-label">
          {{ point.minute }}
        </span>
      </div>
    </div>
    <el-empty v-else description="近 30 分钟暂无竞价数据" :image-size="60" />
  </el-card>
</template>
