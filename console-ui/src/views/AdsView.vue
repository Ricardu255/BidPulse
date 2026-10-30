<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeAdStatus, getAdDetail, getAds, updateAdBudget } from '@/api/console'
import type { AdDetail, AdView } from '@/api/types'
import { errorText, money } from '@/format'

type TagType = 'success' | 'warning' | 'danger' | 'info'

const filters = reactive<{
  status?: number
  advertiserId?: number
  titleLike: string
}>({
  status: undefined,
  advertiserId: undefined,
  titleLike: '',
})

const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })
const ads = ref<AdView[]>([])
const loading = ref(false)

const drawer = ref(false)
const detail = ref<AdDetail | null>(null)
const detailLoading = ref(false)

function statusLabel(status: number): string {
  if (status === 1) return '在投'
  if (status === 2) return '暂停'
  if (status === 3) return '已耗尽'
  return '未知'
}

function statusType(status: number): TagType {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  if (status === 3) return 'danger'
  return 'info'
}

function dimLabel(dimType: number): string {
  const labels: Record<number, string> = {
    1: '地域',
    2: '年龄段',
    3: '性别',
    4: '兴趣',
    5: '系统',
    6: '设备',
  }
  return labels[dimType] ?? `维度 ${dimType}`
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

async function load(pageNum = page.pageNum): Promise<void> {
  loading.value = true
  try {
    const result = await getAds({
      pageNum,
      pageSize: page.pageSize,
      status: filters.status,
      advertiserId: filters.advertiserId,
      titleLike: filters.titleLike.trim() || undefined,
    })
    ads.value = result.list
    page.pageNum = result.pageNum
    page.pageSize = result.pageSize
    page.total = result.total
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.pageNum = 1
  load(1)
}

function reset(): void {
  filters.status = undefined
  filters.advertiserId = undefined
  filters.titleLike = ''
  search()
}

async function openDetail(row: AdView): Promise<void> {
  drawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getAdDetail(row.id)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function toggleStatus(row: AdView): Promise<void> {
  const next = row.status === 1 ? 2 : 1
  const action = next === 2 ? '暂停' : '恢复'
  try {
    await ElMessageBox.confirm(
      next === 2
        ? `暂停广告 #${row.id} 后，它不再参与竞价。继续？`
        : `恢复广告 #${row.id}？若预算仍为负数，下一次竞价可能再次标记为已耗尽。`,
      `${action}广告`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    )
    await changeAdStatus(row.id, next)
    ElMessage.success(`已${action}广告 #${row.id}`)
    await load()
    if (detail.value?.ad.id === row.id) {
      detail.value = await getAdDetail(row.id)
    }
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(errorText(e))
  }
}

async function editBudget(row: AdView): Promise<void> {
  try {
    const result = await ElMessageBox.prompt(
      `当前日预算 ${money(row.dailyBudget)}，输入新预算（元）`,
      `调整预算 · 广告 #${row.id}`,
      {
        inputValue: String(row.dailyBudget),
        inputPattern: /^\d+(\.\d{1,2})?$/,
        inputErrorMessage: '请输入合法金额，最多两位小数',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
      },
    )
    const value = Number(result.value)
    if (!Number.isFinite(value) || value < 1) {
      ElMessage.error('日预算应不小于 1.00 元')
      return
    }
    await updateAdBudget(row.id, value)
    ElMessage.success(`广告 #${row.id} 日预算已调整为 ${money(value)}`)
    await load()
    if (detail.value?.ad.id === row.id) {
      detail.value = await getAdDetail(row.id)
    }
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(errorText(e))
  }
}

onMounted(() => load(1))
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>广告与投放</h1>
        <p>查看广告列表、定向条件与投放状态；支持暂停/恢复和调整当日预算。</p>
      </div>
      <el-tag type="info" effect="plain">GET /console-api/v1/ads</el-tag>
    </header>

    <el-card class="panel" shadow="never">
      <div class="filters">
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px">
          <el-option :value="1" label="在投" />
          <el-option :value="2" label="暂停" />
          <el-option :value="3" label="已耗尽" />
        </el-select>
        <el-input-number
          v-model="filters.advertiserId"
          :min="1"
          placeholder="广告主 ID"
          controls-position="right"
        />
        <el-input v-model="filters.titleLike" placeholder="标题包含…" clearable style="width: 200px" />
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>广告列表</template>
      <el-table v-loading="loading" :data="ads" stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="advertiserId" label="广告主" width="80" />
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column prop="categoryCode" label="类目" width="90" />
        <el-table-column label="出价" width="90">
          <template #default="{ row }">{{ money(row.bid) }}</template>
        </el-table-column>
        <el-table-column label="日预算" width="100">
          <template #default="{ row }">{{ money(row.dailyBudget) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="warning" @click="toggleStatus(row)">
              {{ row.status === 1 ? '暂停' : '恢复' }}
            </el-button>
            <el-button link type="primary" @click="editBudget(row)">预算</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          v-model:current-page="page.pageNum"
          v-model:page-size="page.pageSize"
          background
          layout="total, prev, pager, next, sizes"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <el-drawer v-model="drawer" title="广告详情" size="440px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="drawer-title">#{{ detail.ad.id }} · {{ detail.ad.title }}</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="广告主">{{ detail.ad.advertiserId }}</el-descriptions-item>
            <el-descriptions-item label="类目">{{ detail.ad.categoryCode }}</el-descriptions-item>
            <el-descriptions-item label="出价">{{ money(detail.ad.bid) }}</el-descriptions-item>
            <el-descriptions-item label="日预算">{{ money(detail.ad.dailyBudget) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusType(detail.ad.status)" size="small">
                {{ statusLabel(detail.ad.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatTime(detail.ad.updatedAt) }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="drawer-section">定向条件</h4>
          <el-table :data="detail.targets" size="small" stripe>
            <el-table-column label="维度" width="110">
              <template #default="{ row }">{{ dimLabel(row.dimType) }}</template>
            </el-table-column>
            <el-table-column prop="dimValue" label="取值" />
          </el-table>
          <p class="hint">定向条件参与 ad JOIN ad_target 检索；没有定向的广告永远不会被命中。</p>
        </template>
      </div>
    </el-drawer>
  </div>
</template>
