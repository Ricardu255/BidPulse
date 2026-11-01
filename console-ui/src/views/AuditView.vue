<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { getAuditLogs } from '@/api/console'
import type { AuditLogView } from '@/api/types'
import { errorText } from '@/format'

const logs = ref<AuditLogView[]>([])
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    logs.value = await getAuditLogs(100)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function statusType(status: number): 'success' | 'danger' | 'warning' | 'info' {
  if (status >= 200 && status < 300) return 'success'
  if (status === 401) return 'warning'
  if (status >= 400) return 'danger'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader
      title="审计日志"
      description="管理员查看登录、控制台/管理端写操作与 401/403 拒绝事件。"
      tag="仅 ADMIN"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="formula">
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <span>最近审计事件</span>
          <span class="hint">记录登录、非 GET 写操作与 401/403 拒绝；登录只记录用户名，不记录密码。</span>
        </div>
      </template>

      <el-table v-loading="loading" :data="logs" row-key="id" empty-text="暂无审计记录">
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column prop="actor" label="操作人" width="120" />
        <el-table-column prop="role" label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="method" label="方法/事件" width="110" />
        <el-table-column prop="path" label="路径" min-width="260" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>
