<script setup lang="ts">
import { computed } from 'vue'
import type { TrendPoint } from '@/api/types'

const props = defineProps<{ trend: TrendPoint[] }>()

const WINDOW_MINUTES = 30
const MINUTES_PER_DAY = 24 * 60

/** 把服务端的 `HH:mm` 标签换算成当天第几分钟，解析失败返回 null。 */
function parseMinute(label: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(label)
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (hours > 23 || minutes > 59) return null
  return hours * 60 + minutes
}

function formatMinute(totalMinutes: number): string {
  const normalized = ((totalMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
  const hours = Math.floor(normalized / 60)
  return `${String(hours).padStart(2, '0')}:${String(normalized % 60).padStart(2, '0')}`
}

const buckets = computed(() => {
  const byMinute = new Map(props.trend.map((point) => [point.minute, point]))
  // 锚点必须取接口返回的最后一个分钟标签，而不是浏览器时钟：
  // 分钟标签由服务端按 JVM 时区生成，浏览器时区不同或本地时钟偏差超过一分钟时，
  // 按本地时间拼出来的 30 个 key 全部查不到，totalBids 恒为 0，
  // 面板会误报「暂无竞价数据」——而接口其实返回了数据。
  const lastLabel = props.trend.length > 0 ? props.trend[props.trend.length - 1].minute : undefined
  const anchor = lastLabel === undefined ? null : parseMinute(lastLabel)
  if (anchor === null) return []

  const result: Array<TrendPoint & { zero: boolean }> = []
  for (let i = WINDOW_MINUTES - 1; i >= 0; i -= 1) {
    const minute = formatMinute(anchor - i)
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
