<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import FilterBar from '@/components/FilterBar.vue'
import { getKafkaGroups, pauseConsumers, resumeConsumers } from '@/api/console'
import type { KafkaGroupView, KafkaPartitionView } from '@/api/types'
import { errorText } from '@/format'
import { usePolling } from '@/composables/usePolling'

const props = defineProps<{ active: boolean }>()

const groups = ref<KafkaGroupView[]>([])
const loading = ref(false)
const auto = ref(true)

const partitions = ref<Array<KafkaPartitionView & { group: string }>>([])

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

async function refreshGroups(): Promise<void> {
  loading.value = true
  try {
    groups.value = await getKafkaGroups()
    partitions.value = groups.value.flatMap((group) =>
      group.partitions.map((partition) => ({ group: group.group, ...partition })),
    )
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

async function pause(): Promise<void> {
  try {
    await ElMessageBox.confirm('暂停后消费组停止拉取，/bid 主链路不受影响，lag 会上升。继续？', '暂停消费', {
      type: 'warning',
      confirmButtonText: '暂停',
      cancelButtonText: '取消',
    })
    await pauseConsumers()
    ElMessage.success('已暂停全部消费组')
    await refreshGroups()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    notifyError(e)
  }
}

async function resume(): Promise<void> {
  try {
    await resumeConsumers()
    ElMessage.success('已恢复全部消费组')
    await refreshGroups()
  } catch (e) {
    notifyError(e)
  }
}

usePolling(refreshGroups, 2000, () => auto.value && props.active)
onMounted(refreshGroups)
</script>

<template>
  <el-card class="panel" shadow="never">
    <FilterBar>
      <el-button :loading="loading" @click="refreshGroups">刷新</el-button>
      <el-button type="warning" plain @click="pause">暂停消费</el-button>
      <el-button type="success" plain @click="resume">恢复消费</el-button>
      <el-switch v-model="auto" active-text="自动刷新（2s）" />
      <span class="hint">lag = end - committed，负值按 0 处理。</span>
    </FilterBar>
  </el-card>

  <div class="group-grid">
    <div v-for="group in groups" :key="group.group" class="group-card">
      <div class="group-head">
        <strong>{{ group.group }}</strong>
        <el-tag :type="group.exists ? (group.partitionCount ? 'success' : 'warning') : 'info'" size="small">
          {{ group.exists ? (group.partitionCount ? '已注册' : '无位点') : '未注册' }}
        </el-tag>
      </div>
      <div class="lag" :class="{ hot: group.totalLag > 0 }">{{ group.totalLag }}</div>
      <small>分区 {{ group.partitionCount }} · 总积压</small>
    </div>
  </div>

  <el-card class="panel" shadow="never">
    <template #header>分区明细</template>
    <el-table :data="partitions" stripe size="small" max-height="420">
      <el-table-column prop="group" label="消费组" width="180" />
      <el-table-column prop="partition" label="分区" width="80" />
      <el-table-column prop="committed" label="committed" width="140" />
      <el-table-column prop="end" label="end" width="140" />
      <el-table-column label="lag" width="120">
        <template #default="{ row }">
          <el-tag :type="row.lag > 0 ? 'warning' : 'success'" size="small">{{ row.lag }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!partitions.length" description="暂无已提交位点的分区" />
  </el-card>
</template>
