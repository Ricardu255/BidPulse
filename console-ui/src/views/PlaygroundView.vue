<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { runBid, runBurst } from '@/api/console'
import type { BidRequest, BidResult, BurstRow } from '@/api/types'
import { errorText, money } from '@/format'
import { remember } from '@/session'

const route = useRoute()

const form = reactive<BidRequest>({
  slotCode: 'slot-01',
  region: '110000',
  age: 28,
  gender: 1,
  categoryCode: '',
  userId: 'learner-1',
})

const loading = ref(false)
const bursting = ref(false)
const showAdvanced = ref(false)
const last = ref<BidResult | null>(null)
const wallMs = ref<number | null>(null)
const burstRows = ref<BurstRow[]>([])

const resultTitle = computed(() => {
  if (!last.value) return ''
  if (last.value.fallback) return '兜底广告'
  return last.value.hasAd ? '竞价成功' : '无广告'
})

const modeLabel = computed(() => {
  if (!last.value) return ''
  if (!last.value.hasAd) return '无广告'
  return priceModeLabel(last.value.priceMode)
})
type TagType = 'success' | 'warning' | 'info'
const modeTagType = computed<TagType>(() => {
  if (last.value && !last.value.hasAd) return 'info'
  if (last.value?.priceMode === 1) return 'success'
  if (last.value?.priceMode === 2) return 'warning'
  return 'info'
})
const savings = computed(() => {
  const win = last.value?.winner?.bid
  const pay = last.value?.payPrice
  if (win == null || pay == null) return ''
  return `比出价低 ${money(Number(win) - Number(pay))}`
})

function priceModeLabel(mode: number | null | undefined): string {
  if (mode === 0) return '一价'
  if (mode === 1) return '二价'
  if (mode === 2) return '兜底'
  return '—'
}

function buildPayload(): BidRequest {
  return {
    ...form,
    userId: form.userId?.trim() || undefined,
    categoryCode: form.categoryCode?.trim() || undefined,
  }
}

async function submitBid(): Promise<void> {
  loading.value = true
  try {
    const { result, wallMs: elapsed } = await runBid(buildPayload())
    last.value = result
    wallMs.value = elapsed
    remember(result.winner?.adId, form.userId)
    if (result.fallback) {
      ElMessage.warning('本次返回兜底广告，未扣预算')
    }
  } catch (e) {
    ElMessage({ message: errorText(e), type: 'error', grouping: true })
  } finally {
    loading.value = false
  }
}

async function submitBurst(): Promise<void> {
  bursting.value = true
  burstRows.value = []
  try {
    burstRows.value = await runBurst(buildPayload(), 4)
    const lastRow = burstRows.value[burstRows.value.length - 1]
    if (lastRow?.winnerId != null) {
      remember(lastRow.winnerId, form.userId)
    }
    const failures = burstRows.value.filter((row) => row.error).length
    if (failures === burstRows.value.length) {
      ElMessage.error('连打全部失败，请检查后端与网络')
    } else if (failures > 0) {
      ElMessage.warning(`连打完成，其中 ${failures} 次失败`)
    } else {
      ElMessage.success('连打完成')
    }
  } catch (e) {
    ElMessage({ message: errorText(e), type: 'error', grouping: true })
  } finally {
    bursting.value = false
  }
}

// 演示/截图用：/#/playground?autobid=1 打开后自动跑一次竞价
onMounted(() => {
  if (route.query.autobid === '1') {
    submitBid()
  }
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>模拟竞价</h1>
        <p>模拟一次流量请求，观察候选、二价成交价和频次拦截；不用写代码。</p>
      </div>
      <el-tag type="info" effect="plain">POST /console-api/v1/bids</el-tag>
    </header>

    <el-card class="panel" shadow="never">
      <template #header>请求参数</template>
      <el-form :model="form" label-position="top">
        <div class="form-grid">
          <el-form-item v-if="showAdvanced" label="广告位 slotCode">
            <el-input v-model="form.slotCode" />
          </el-form-item>
          <el-form-item label="地域 region">
            <el-input v-model="form.region" />
          </el-form-item>
          <el-form-item label="年龄 age">
            <el-input-number v-model="form.age" :min="0" :max="120" controls-position="right" />
          </el-form-item>
          <el-form-item label="性别 gender">
            <el-select v-model="form.gender" placeholder="选择性别">
              <el-option :value="1" label="男" />
              <el-option :value="2" label="女" />
              <el-option :value="0" label="未知" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="showAdvanced" label="类目 categoryCode">
            <el-input v-model="form.categoryCode" placeholder="可空" />
          </el-form-item>
          <el-form-item label="用户 userId">
            <el-input v-model="form.userId" placeholder="用于实时频次控制" />
          </el-form-item>
        </div>

        <div class="actions">
          <el-button type="primary" :loading="loading" @click="submitBid">发起竞价</el-button>
          <el-button :loading="bursting" @click="submitBurst">连打 4 次</el-button>
          <el-button link type="primary" @click="showAdvanced = !showAdvanced">
            {{ showAdvanced ? '收起高级参数' : '高级参数' }}
          </el-button>
          <span class="hint">默认值已够演示；需要改广告位/类目时再展开。</span>
        </div>
      </el-form>
    </el-card>

    <el-card v-if="last" class="panel" shadow="never">
      <div class="result-head">
        <div>
          <span class="eyebrow">竞价结果</span>
          <h2>{{ resultTitle }}</h2>
          <p>{{ last?.requestId }}</p>
        </div>
        <el-tag :type="modeTagType" size="large">{{ modeLabel }}</el-tag>
      </div>

      <div class="metric-grid">
        <div class="metric">
          <span>胜出广告</span>
          <strong>{{ last?.winner?.adId ?? '—' }}</strong>
          <small>{{ last?.winner?.title || '无' }}</small>
        </div>
        <div class="metric">
          <span>胜出价 winBid</span>
          <strong>{{ money(last?.winner?.bid) }}</strong>
        </div>
        <div class="metric">
          <span>成交价 payPrice</span>
          <strong>{{ money(last?.payPrice) }}</strong>
          <small>{{ savings }}</small>
        </div>
        <div class="metric">
          <span>候选 / 匹配</span>
          <strong>{{ last?.candidateCnt }} / {{ last?.matchCnt }}</strong>
        </div>
        <div class="metric">
          <span>耗时 wallMs</span>
          <strong>{{ wallMs ?? '—' }} ms</strong>
        </div>
      </div>

      <el-alert
        v-if="last?.priceMode === 1"
        class="formula"
        type="success"
        :closable="false"
        show-icon
        title="二价计费：成交价 = min(胜出价, 次高有效出价 + 0.01)"
      />
      <el-alert
        v-if="last?.fallback"
        class="formula"
        type="warning"
        :closable="false"
        show-icon
        title="没有可投放的真实广告，已返回兜底广告"
        description="兜底广告 adId=999999 是保底广告，不是广告主投放；出价只是配置值，payPrice 为空，不计费（priceMode=2）。"
      />

      <el-table :data="last?.topCandidates ?? []" stripe size="small">
        <el-table-column prop="adId" label="adId" width="110" />
        <el-table-column prop="title" label="广告标题" min-width="220" />
        <el-table-column prop="categoryCode" label="类目" width="120" />
        <el-table-column label="出价" width="120">
          <template #default="{ row }">{{ money(row.bid) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.adId === last?.winner?.adId" type="success" size="small">胜出</el-tag>
            <span v-else class="hint">候选</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-empty v-else description="发起一次竞价，查看胜出广告与成交价" />

    <el-card v-if="burstRows.length" class="panel" shadow="never">
      <template #header>连打结果</template>
      <el-table :data="burstRows" stripe size="small">
        <el-table-column prop="index" label="#" width="56" />
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.error" type="danger" size="small">错误</el-tag>
            <el-tag v-else-if="!row.hasAd" type="info" size="small">空广告</el-tag>
            <el-tag v-else-if="row.fallback" type="warning" size="small">兜底</el-tag>
            <el-tag v-else type="success" size="small">胜出</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="winnerId" label="胜出 adId" width="130" />
        <el-table-column label="成交价" width="130">
          <template #default="{ row }">{{ money(row.payPrice) }}</template>
        </el-table-column>
        <el-table-column label="计费模式" width="110">
          <template #default="{ row }">{{ row.hasAd ? priceModeLabel(row.priceMode) : '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">{{ row.wallMs ?? '—' }} ms</template>
        </el-table-column>
        <el-table-column prop="error" label="错误" min-width="180" />
      </el-table>
      <p class="hint">第 4 次起，同一 userId 对同一广告应达到 3 次上限；到「预算与频次」页查看计数。</p>
    </el-card>
  </div>
</template>
