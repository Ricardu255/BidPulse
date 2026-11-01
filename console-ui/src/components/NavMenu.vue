<script setup lang="ts">
import { computed } from 'vue'
import { auth } from '@/auth'

defineProps<{ active: string }>()
const emit = defineEmits<{ (e: 'select'): void }>()

const isAdmin = computed(() => auth.user?.role === 'ADMIN')
</script>

<template>
  <el-menu :default-active="active" router class="nav" @select="emit('select')">
    <el-menu-item-group title="监控">
      <el-menu-item index="/overview">首页</el-menu-item>
      <el-menu-item index="/ops">运行监控</el-menu-item>
      <el-menu-item index="/data">数据查询</el-menu-item>
      <el-menu-item v-if="isAdmin" index="/audit">审计日志</el-menu-item>
    </el-menu-item-group>
    <el-menu-item-group title="投放">
      <el-menu-item index="/playground">模拟竞价</el-menu-item>
      <el-menu-item index="/ads">广告管理</el-menu-item>
      <el-menu-item index="/budget">预算与频次</el-menu-item>
    </el-menu-item-group>
    <el-menu-item-group title="演示">
      <el-menu-item index="/experiments">演示开关</el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>
