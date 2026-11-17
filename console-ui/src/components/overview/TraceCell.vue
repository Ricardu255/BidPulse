<script setup lang="ts">
import type { RecentBidView } from '@/api/types'

/**
 * 「轨迹」单元格。
 *
 * <p>同一段按钮在概览页出现过三次（桌面表、移动行、最近 50 条抽屉），
 * 之前三处各写一份，改文案或加权限判断时很容易漏掉一处。
 */
defineProps<{
  row: RecentBidView
  label?: string
}>()

defineEmits<{
  open: [row: RecentBidView]
}>()
</script>

<template>
  <el-button v-if="row.hasTrace" link type="primary" size="small" @click="$emit('open', row)">
    {{ label ?? '查看' }}
  </el-button>
  <span v-else class="hint" title="该条请求没有落轨迹（明细可能还没入库）">—</span>
</template>
