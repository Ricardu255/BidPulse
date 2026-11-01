<script setup lang="ts">
import FilterBar from '@/components/FilterBar.vue'
import PageHeader from '@/components/PageHeader.vue'
import AdStatusTag from '@/components/AdStatusTag.vue'
import AdFormDialog from '@/components/ads/AdFormDialog.vue'
import AdDetailDrawer from '@/components/ads/AdDetailDrawer.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeAdStatus, getAdDetail, getAds, updateAdBudget } from '@/api/console'
import type { AdDetail, AdView } from '@/api/types'
import { errorText, money } from '@/format'
import { useAsyncAction } from '@/composables/useAsyncAction'
import { hasRole } from '@/auth'

const filters = reactive<{
  status?: number
  advertiserId?: number
  titleLike: string
}>({
  status: undefined,
  advertiserId: undefined,
  titleLike: '',
})

const canWrite = hasRole('OPERATOR', 'ADMIN')

const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })
const ads = ref<AdView[]>([])
const loading = ref(false)

const drawer = ref(false)
const detail = ref<AdDetail | null>(null)

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const editingStatus = ref<number | null>(null)
const editingDetail = ref<AdDetail | null>(null)

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

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

const { loading: detailLoading, run: loadDetail } = useAsyncAction(
  async (row: AdView) => {
    detail.value = await getAdDetail(row.id)
  },
  notifyError,
)

async function openDetail(row: AdView): Promise<void> {
  drawer.value = true
  detail.value = null
  await loadDetail(row)
}

function openCreate(): void {
  editingId.value = null
  editingStatus.value = null
  editingDetail.value = null
  formVisible.value = true
}

async function openEdit(row: AdView): Promise<void> {
  try {
    editingDetail.value = await getAdDetail(row.id)
    editingId.value = row.id
    editingStatus.value = editingDetail.value.ad.status
    formVisible.value = true
  } catch (e) {
    notifyError(e)
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
    <PageHeader
      title="广告管理"
      description="管理可投放广告；新建、编辑、暂停或调预算后，下一次竞价立即生效。"
    >
      <template #actions>
        <div class="actions">
          <el-button v-if="canWrite" type="primary" @click="openCreate">新建广告</el-button>
          <el-tag type="info" effect="plain">GET /console-api/v1/ads</el-tag>
        </div>
      </template>
    </PageHeader>

    <el-card class="panel" shadow="never">
      <FilterBar>
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
      </FilterBar>
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
            <AdStatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" link type="warning" @click="toggleStatus(row)">
              {{ row.status === 1 ? '暂停' : '恢复' }}
            </el-button>
            <el-button v-if="canWrite" link type="primary" @click="editBudget(row)">预算</el-button>
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

    <AdFormDialog
      v-model="formVisible"
      :editing-id="editingId"
      :editing-status="editingStatus"
      :detail="editingDetail"
      @saved="load"
    />

    <AdDetailDrawer v-model="drawer" :detail="detail" :loading="detailLoading" />
  </div>
</template>
