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
  const circumference = 2 * Math.PI * 50
  const winLength = total ? (win / total) * circumference : 0
  const fallbackLength = total ? (fallback / total) * circumference : 0
  return { win, fallback, empty, total, circumference, winLength, fallbackLength }
})
</script>

<template>
  <el-card class="panel" shadow="never">
    <template #header>最近结果分布</template>
    <div class="donut-wrap">
      <div class="donut">
        <svg class="donut-svg" viewBox="0 0 120 120" role="img" aria-label="最近竞价结果分布">
          <circle class="donut-track" cx="60" cy="60" r="50" />
          <circle
            v-if="distribution.winLength"
            class="donut-segment win"
            cx="60"
            cy="60"
            r="50"
            :stroke-dasharray="`${distribution.winLength} ${distribution.circumference}`"
          />
          <circle
            v-if="distribution.fallbackLength"
            class="donut-segment fallback"
            cx="60"
            cy="60"
            r="50"
            :stroke-dasharray="`${distribution.fallbackLength} ${distribution.circumference}`"
            :stroke-dashoffset="`-${distribution.winLength}`"
          />
        </svg>
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
