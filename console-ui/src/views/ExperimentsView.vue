<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getExperiments, updateExperiments } from '@/api/console'
import type { ExperimentsView } from '@/api/types'
import { errorText } from '@/format'

const experiments = ref<ExperimentsView | null>(null)
const loading = ref(false)
const saving = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    experiments.value = await getExperiments()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (!experiments.value) return
  saving.value = true
  try {
    experiments.value = await updateExperiments({
      fallbackEnabled: experiments.value.fallbackEnabled,
      fallbackForce: experiments.value.fallbackForce,
      frequencyEnabled: experiments.value.frequencyEnabled,
      frequencyMaxPerHour: experiments.value.frequencyMaxPerHour,
    })
    ElMessage.success('实验开关已更新，下一次请求立即生效')
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>实验与开关</h1>
        <p>运行时开关：兜底、频次上限。只暴露可以即时生效、不会破坏数据一致性的配置。</p>
      </div>
      <el-tag type="info" effect="plain">GET /console-api/v1/experiments</el-tag>
    </header>

    <el-card v-if="experiments" v-loading="loading" class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>运行时配置</span>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </template>

      <el-alert
        v-if="!experiments.fallbackEnabled"
        type="error"
        :closable="false"
        show-icon
        title="兜底已关闭：依赖故障时可能返回空广告，仅用于 A/B 对照。"
        class="formula"
      />
      <el-alert
        v-if="experiments.fallbackForce"
        type="warning"
        :closable="false"
        show-icon
        title="强制兜底已开启：所有请求都会返回兜底广告，不参与竞价。"
        class="formula"
      />

      <div class="experiment-grid">
        <div class="experiment-item">
          <div>
            <strong>兜底开关</strong>
            <p class="hint">关闭后不会返回兜底广告，用于复现旧实现。</p>
          </div>
          <el-switch v-model="experiments.fallbackEnabled" />
        </div>

        <div class="experiment-item">
          <div>
            <strong>强制兜底</strong>
            <p class="hint">开启后每次都走兜底，用于模拟依赖全面故障。</p>
          </div>
          <el-switch v-model="experiments.fallbackForce" />
        </div>

        <div class="experiment-item">
          <div>
            <strong>实时频次控制</strong>
            <p class="hint">关闭后不读写频次 key，匿名与登录请求都不控频。</p>
          </div>
          <el-switch v-model="experiments.frequencyEnabled" />
        </div>

        <div class="experiment-item">
          <div>
            <strong>每用户每小时上限</strong>
            <p class="hint">同一用户对同一广告的自然小时最大成交次数。</p>
          </div>
          <el-input-number
            v-model="experiments.frequencyMaxPerHour"
            :min="1"
            :max="100"
            controls-position="right"
          />
        </div>
      </div>

      <el-divider />
      <div class="metric-grid three">
        <div class="metric">
          <span>明细同步写</span>
          <strong>{{ experiments.detailSyncInsert ? '开启' : '关闭' }}</strong>
          <small>只读 · bidpulse.detail.sync-insert</small>
        </div>
        <div class="metric">
          <span>控制台写操作</span>
          <strong>{{ experiments.consoleAllowWrite ? '允许' : '禁用' }}</strong>
          <small>只读 · bidpulse.console.allow-write</small>
        </div>
        <div class="metric">
          <span>生效方式</span>
          <strong>下一次请求</strong>
          <small>配置对象实时读取</small>
        </div>
      </div>
    </el-card>

    <el-empty v-else v-loading="loading" description="加载实验中" />
  </div>
</template>
