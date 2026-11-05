<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  stale: boolean
  lastUpdatedAt: number | null
}>()

const updatedText = computed(() => {
  if (!props.lastUpdatedAt) return '—'
  return new Date(props.lastUpdatedAt).toLocaleTimeString('zh-CN', { hour12: false })
})
</script>

<template>
  <div v-if="stale" class="stale-notice">
    <el-tag type="warning" size="small">数据可能已过期</el-tag>
    <span>最近一次成功刷新 {{ updatedText }}；后台刷新失败，页面保留上次数据。</span>
  </div>
  <div v-else-if="lastUpdatedAt" class="fresh-notice">更新于 {{ updatedText }}</div>
</template>

<style scoped>
.stale-notice,
.fresh-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  padding: 8px 12px;
  border: 1px solid var(--bp-line);
  border-radius: 6px;
  background: var(--bp-panel);
  color: var(--bp-muted);
  font-size: 12px;
  line-height: 1.5;
}

.stale-notice {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}

.fresh-notice {
  justify-content: flex-end;
  border-color: transparent;
  background: transparent;
  padding: 0 2px 8px;
}
</style>
