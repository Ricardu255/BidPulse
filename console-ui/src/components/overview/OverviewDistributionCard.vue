<script setup lang="ts">
import { computed } from 'vue'
import type { RecentBidView } from '@/api/types'
import { FALLBACK_AD_ID } from '@/constants'

const props = defineProps<{ recent: RecentBidView[] }>()

const distribution = computed(() => {
  let win = 0
  let fallback = 0
  let empty = 0
  for (const row of props.recent) {
    if (!row.hasAd) empty += 1
    else if (row.adId === FALLBACK_AD_ID) fallback += 1
    else win += 1
  }
  const total = win + fallback + empty
  const winEnd = total ? (win / total) * 100 : 0
  const fallbackEnd = total ? winEnd + (fallback / total) * 100 : 0
  const gradient = total
    ? `conic-gradient(#5b83a6 0 ${winEnd}%, #b9853c ${winEnd}% ${fallbackEnd}%, #c9d9e6 ${fallbackEnd}% 100%)`
    : 'conic-gradient(#e4edf4 0 100%)'
  return { win, fallback, empty, total, gradient }
})
</script>

<template>
  <el-card class="panel" shadow="never">
    <template #header>最近结果分布</template>
    <div class="donut-wrap">
      <div class="donut" :style="{ background: distribution.gradient }">
        <div class="donut-center">
          <strong>{{ distribution.total }}</strong>
          <small>最近竞价</small>
        </div>
      </div>
      <div class="legend">
        <div><span class="legend-dot win"></span>胜出 {{ distribution.win }}</div>
        <div><span class="legend-dot fallback"></span>兜底 {{ distribution.fallback }}</div>
        <div><span class="legend-dot empty"></span>空广告 {{ distribution.empty }}</div>
      </div>
    </div>
  </el-card>
</template>
