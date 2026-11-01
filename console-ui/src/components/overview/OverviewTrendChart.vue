<script setup lang="ts">
import { computed } from 'vue'
import type { TrendPoint } from '@/api/types'

const props = defineProps<{ trend: TrendPoint[] }>()

const maxBids = computed(() => {
  return Math.max(1, ...props.trend.map((point) => point.bids))
})

function barHeight(bids: number): number {
  return Math.max(4, Math.round((bids / maxBids.value) * 110))
}
</script>

<template>
  <el-card class="panel" shadow="never">
    <template #header>近 30 分钟请求趋势</template>
    <div v-if="trend.length" class="trend">
      <div v-for="(point, index) in trend" :key="point.minute" class="trend-col">
        <div
          class="trend-bar"
          :style="{ height: `${barHeight(point.bids)}px` }"
          :title="`${point.minute} · ${point.bids} 次 · 兜底 ${point.fallback}`"
        ></div>
        <span v-if="index % 5 === 0 || index === trend.length - 1" class="trend-label">
          {{ point.minute }}
        </span>
      </div>
    </div>
    <el-empty v-else description="近 30 分钟暂无竞价数据" :image-size="60" />
  </el-card>
</template>
