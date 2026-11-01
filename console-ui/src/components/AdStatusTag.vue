<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: number
  size?: 'small' | 'default' | 'large'
}>()

const label = computed(() => {
  switch (props.status) {
    case 1:
      return '在投'
    case 2:
      return '暂停'
    case 3:
      return '已耗尽'
    default:
      return '未知'
  }
})

const type = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (props.status) {
    case 1:
      return 'success'
    case 2:
      return 'warning'
    case 3:
      return 'danger'
    default:
      return 'info'
  }
})
</script>

<template>
  <el-tag :type="type" :size="size ?? 'small'">{{ label }}</el-tag>
</template>
