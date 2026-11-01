<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import HelpDrawer from '@/components/HelpDrawer.vue'
import NavMenu from '@/components/NavMenu.vue'
import { auth, clearAuth, roleLabel } from '@/auth'

const route = useRoute()
const router = useRouter()
const title = computed(() => (route.meta.title as string | undefined) ?? 'BidPulse Console')
const active = computed(() => route.path)
const helpVisible = ref(false)
const mobileNav = ref(false)
const isPublic = computed(() => route.meta.public === true)
const readOnly = computed(() => auth.user?.role === 'VIEWER')

function logout(): void {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <router-view v-if="isPublic" />

  <el-container v-else class="app-shell">
    <el-aside width="228px" class="sidebar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true"></span>
        <div>
          <strong>BidPulse</strong>
          <small>实时竞价控制台</small>
        </div>
      </div>

      <NavMenu :active="active" />

      <div class="sidebar-foot">
        <span>{{ readOnly ? '只读模式' : '演示环境' }}</span>
        <p>监控 · 投放 · 预算 · 异步链路</p>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-head">
          <el-button class="mobile-menu-btn" text size="small" aria-label="打开导航" @click="mobileNav = true">
            目录
          </el-button>
          <div class="topbar-title"><span class="crumb-root">投放控制台</span> / {{ title }}</div>
        </div>
        <div class="topbar-actions">
          <el-tag v-if="readOnly" type="info" effect="plain" size="small">只读账号</el-tag>
          <span class="user-chip">{{ auth.user?.username }} · {{ roleLabel(auth.user?.role) }}</span>
          <el-button size="small" text @click="helpVisible = true">使用说明</el-button>
          <el-button size="small" text @click="logout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-drawer v-model="mobileNav" direction="ltr" size="264px" title="BidPulse">
      <NavMenu :active="active" @select="mobileNav = false" />
    </el-drawer>

    <HelpDrawer v-model="helpVisible" />
  </el-container>
</template>
