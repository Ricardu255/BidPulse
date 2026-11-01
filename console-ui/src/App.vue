<script setup lang="ts">
import { computed, ref } from 'vue'
import HelpDrawer from '@/components/HelpDrawer.vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, clearAuth, roleLabel } from '@/auth'

const route = useRoute()
const router = useRouter()
const title = computed(() => (route.meta.title as string | undefined) ?? 'BidPulse Console')
const active = computed(() => route.path)
const helpVisible = ref(false)
const isPublic = computed(() => route.meta.public === true)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const readOnly = computed(() => auth.user?.role === 'VIEWER')

function logout(): void {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <router-view v-if="isPublic" />

  <el-container v-else class="app-shell">
    <el-aside width="232px" class="sidebar">
      <div class="brand">
        <span class="brand-mark"></span>
        <div>
          <strong>BidPulse</strong>
          <small>Console</small>
        </div>
      </div>

      <el-menu :default-active="active" router class="nav">
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

      <div class="sidebar-foot">
        <span>{{ readOnly ? 'READ-ONLY' : 'DEMO' }}</span>
        <p>首页 · 模拟 · 投放 · 监控 · 查询 · 开关</p>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">{{ title }}</div>
        <div class="topbar-actions">
          <el-tag v-if="readOnly" type="info" effect="plain" size="small">只读账号</el-tag>
          <span class="user-chip">
            {{ auth.user?.username }} · {{ roleLabel(auth.user?.role) }}
          </span>
          <el-button size="small" @click="helpVisible = true">使用说明</el-button>
          <el-button size="small" plain @click="logout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <HelpDrawer v-model="helpVisible" />
  </el-container>
</template>
