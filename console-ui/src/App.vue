<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string | undefined) ?? 'BidPulse Console')
const active = computed(() => route.path)
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="232px" class="sidebar">
      <div class="brand">
        <span class="brand-mark"></span>
        <div>
          <strong>BidPulse</strong>
          <small>Console</small>
        </div>
      </div>

      <el-menu :default-active="active" router class="nav">
        <el-menu-item index="/overview">概览</el-menu-item>
        <el-menu-item index="/playground">竞价实验室</el-menu-item>
        <el-menu-item index="/ads">广告与投放</el-menu-item>
        <el-menu-item index="/budget">预算与频次</el-menu-item>
        <el-menu-item index="/ops">异步运维</el-menu-item>
      </el-menu>

      <div class="sidebar-foot">
        <span>P1</span>
        <p>概览 · 投放 · 竞价 · 预算 · Kafka</p>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">{{ title }}</div>
        <div class="api-chip">
          <span class="api-dot"></span>
          REST /console-api/v1
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
