<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ health: Record<string, string> }>()

const items = computed(() =>
  Object.entries(props.health ?? {}).map(([key, value]) => ({
    key,
    value,
    label: healthLabel(key),
  })),
)

function healthLabel(key: string): string {
  const labels: Record<string, string> = {
    overall: '整体',
    db: 'MySQL',
    redis: 'Redis',
    kafka: 'Kafka',
    diskSpace: '磁盘',
    ping: 'Ping',
    dbBreaker: 'DB 熔断',
    redisBreaker: 'Redis 熔断',
  }
  return labels[key] ?? key
}

function healthText(value: string): string {
  if (value === 'UP') return '正常'
  if (value === 'DOWN' || value === 'OUT_OF_SERVICE') return '异常'
  if (value === 'CLOSED') return '正常'
  if (value === 'OPEN') return '熔断中'
  if (value === 'HALF_OPEN') return '半开恢复'
  return value
}

function healthType(value: string): 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'UP' || value === 'CLOSED') return 'success'
  if (value === 'HALF_OPEN') return 'warning'
  if (value === 'DOWN' || value === 'OUT_OF_SERVICE' || value === 'OPEN') return 'danger'
  return 'info'
}
</script>

<template>
  <el-card class="panel" shadow="never">
    <template #header>系统健康</template>
    <div class="health-strip">
      <div v-for="item in items" :key="item.key" class="health-chip">
        <span class="health-name">{{ item.label }}</span>
        <el-tag :type="healthType(item.value)" size="small">{{ healthText(item.value) }}</el-tag>
      </div>
      <el-empty v-if="!items.length" description="暂无健康数据" :image-size="60" />
    </div>
  </el-card>
</template>
