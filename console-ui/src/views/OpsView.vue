<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDltMessages,
  getKafkaGroups,
  getReconReport,
  pauseConsumers,
  replayDlt,
  replayDltOne,
  resumeConsumers,
  runRecon,
} from '@/api/console'
import type { DltMessageView, DltReplayStats, KafkaGroupView, ReconReport } from '@/api/types'
import { cents, errorText } from '@/format'

const activeTab = ref<'groups' | 'dlt' | 'recon'>('groups')

const groups = ref<KafkaGroupView[]>([])
const loading = ref(false)
const auto = ref(true)
let timer: ReturnType<typeof setInterval> | undefined

const dltMessages = ref<DltMessageView[]>([])
const dltLoading = ref(false)
const dltLimit = ref(20)
const replayMax = ref(100)
const replaying = ref(false)
const replayingOne = ref<string | null>(null)
const lastReplay = ref<DltReplayStats | null>(null)

const recon = ref<ReconReport | null>(null)
const reconLoading = ref(false)
const reconRunning = ref(false)

const partitions = computed(() =>
  groups.value.flatMap((group) =>
    group.partitions.map((partition) => ({ group: group.group, ...partition })),
  ),
)

function preview(value: string | null): string {
  if (!value) return '—'
  return value.length > 90 ? `${value.slice(0, 90)}…` : value
}

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

function outcomeLabel(outcome: string): string {
  if (outcome === 'REPLAYED') return '已投递主主题'
  if (outcome === 'SKIPPED_INVALID') return '已跳过：报文不可解析或缺少 eventId'
  if (outcome === 'SKIPPED_EXHAUSTED') return '已跳过：达到最大重放次数'
  return outcome
}

function reconType(status: string | undefined): 'success' | 'danger' | 'info' {
  if (status === 'OK') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function directionType(direction: string): 'warning' | 'danger' | 'info' {
  if (direction === 'REDIS_AHEAD') return 'warning'
  if (direction === 'DB_AHEAD') return 'danger'
  return 'info'
}

function actionType(action: string): 'success' | 'warning' | 'danger' {
  if (action === 'REPAIRED') return 'success'
  if (action === 'ALERT') return 'warning'
  return 'danger'
}

async function refreshGroups(): Promise<void> {
  loading.value = true
  try {
    groups.value = await getKafkaGroups()
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

async function refreshDlt(): Promise<void> {
  dltLoading.value = true
  try {
    dltMessages.value = await getDltMessages(dltLimit.value)
  } catch (e) {
    notifyError(e)
  } finally {
    dltLoading.value = false
  }
}

async function refreshRecon(): Promise<void> {
  reconLoading.value = true
  try {
    recon.value = await getReconReport()
  } catch (e) {
    notifyError(e)
  } finally {
    reconLoading.value = false
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

async function triggerReplay(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将 DLT 中可解析、未超过 3 次的消息重放回主主题，本轮上限 ${replayMax.value} 条。继续？`,
      '触发 DLT 重放',
      { type: 'warning', confirmButtonText: '开始重放', cancelButtonText: '取消' },
    )
    replaying.value = true
    lastReplay.value = await replayDlt(replayMax.value)
    ElMessage.success(
      `重放完成：成功 ${lastReplay.value.replayed} 条，跳过不可解析 ${lastReplay.value.skippedInvalid} 条，超限 ${lastReplay.value.skippedExhausted} 条`,
    )
    await Promise.all([refreshDlt(), refreshGroups()])
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    notifyError(e)
  } finally {
    replaying.value = false
  }
}

async function replayOne(row: DltMessageView): Promise<void> {
  const key = `${row.partition}:${row.offset}`
  try {
    await ElMessageBox.confirm(
      `重放 partition=${row.partition} offset=${row.offset}？DLT 原记录会保留，重复消费由 processed_event 幂等吸收。`,
      '单条重放',
      { type: 'warning', confirmButtonText: '重放', cancelButtonText: '取消' },
    )
    replayingOne.value = key
    const result = await replayDltOne(row.partition, row.offset)
    ElMessage.success(`单条重放结果：${outcomeLabel(result.outcome)}`)
    await Promise.all([refreshDlt(), refreshGroups()])
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    notifyError(e)
  } finally {
    replayingOne.value = null
  }
}

async function triggerRecon(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '立即对比 Redis spent 与 billing_event 当日汇总：Redis 低于账单自动修复，高于账单只告警。继续？',
      '立即对账',
      { type: 'warning', confirmButtonText: '开始对账', cancelButtonText: '取消' },
    )
    reconRunning.value = true
    recon.value = await runRecon()
    ElMessage.success('对账完成')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    await refreshRecon()
    notifyError(e)
  } finally {
    reconRunning.value = false
  }
}

function stopTimer(): void {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

function startTimer(): void {
  stopTimer()
  // 只在“消费组”页签轮询 lag；切到 DLT/对账后停止，避免后台无效请求和重复报错。
  if (auto.value && activeTab.value === 'groups') {
    timer = setInterval(refreshGroups, 2000)
  }
}

watch(auto, startTimer)
watch(activeTab, startTimer)

onMounted(() => {
  refreshGroups()
  refreshDlt()
  refreshRecon()
  startTimer()
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>运行监控</h1>
        <p>看异步链路有没有卡住：消费组 lag、DLT 失败消息、Redis 与账单差异。</p>
      </div>
      <el-tag type="info" effect="plain">GET /console-api/v1/kafka/groups</el-tag>
    </header>

    <el-tabs v-model="activeTab" class="ops-tabs">
      <el-tab-pane label="消费组" name="groups">
        <el-card class="panel" shadow="never">
          <div class="filters">
            <el-button :loading="loading" @click="refreshGroups">刷新</el-button>
            <el-button type="warning" plain @click="pause">暂停消费</el-button>
            <el-button type="success" plain @click="resume">恢复消费</el-button>
            <el-switch v-model="auto" active-text="自动刷新（2s）" />
            <span class="hint">lag = end - committed，负值按 0 处理。</span>
          </div>
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
      </el-tab-pane>

      <el-tab-pane label="DLT 重放" name="dlt">
        <el-card class="panel" shadow="never">
          <div class="filters">
            <span class="hint">最近 DLT 消息（含已处理/已跳过）</span>
            <el-input-number v-model="dltLimit" :min="1" :max="50" size="small" />
            <el-button size="small" :loading="dltLoading" @click="refreshDlt">刷新</el-button>
            <el-input-number v-model="replayMax" :min="1" :max="1000" size="small" />
            <el-button size="small" type="warning" plain :loading="replaying" @click="triggerReplay">
              批量重放
            </el-button>
          </div>
        </el-card>

        <el-alert
          v-if="lastReplay"
          type="success"
          :closable="false"
          show-icon
          :title="`上轮结果：重放 ${lastReplay.replayed} 条，跳过不可解析 ${lastReplay.skippedInvalid} 条，超限 ${lastReplay.skippedExhausted} 条`"
          class="formula"
        />

        <el-card class="panel" shadow="never">
          <el-table :data="dltMessages" v-loading="dltLoading" stripe size="small">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="dlt-detail">
                  <div class="dlt-label">value</div>
                  <pre class="dlt-value">{{ row.value }}</pre>
                  <div class="dlt-label">headers</div>
                  <pre class="dlt-value">{{ JSON.stringify(row.headers, null, 2) }}</pre>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="partition" label="分区" width="80" />
            <el-table-column prop="offset" label="offset" width="100" />
            <el-table-column prop="replayCount" label="重放次数" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.processed ? 'info' : 'warning'" size="small">
                  {{ row.processed ? '已处理' : '待处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="key" label="key" width="120" />
            <el-table-column prop="timestamp" label="时间" width="170" />
            <el-table-column label="value 预览" min-width="220">
              <template #default="{ row }">{{ preview(row.value) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="warning"
                  :loading="replayingOne === `${row.partition}:${row.offset}`"
                  @click="replayOne(row)"
                >
                  重放
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!dltMessages.length" description="DLT 暂无待处理消息" :image-size="60" />
          <p class="hint">
            浏览使用独立 assign 消费者，不提交位点；单条重放只投递主主题，不推进定时任务位点。
          </p>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="预算对账" name="recon">
        <el-card class="panel" shadow="never">
          <div class="filters">
            <el-button size="small" :loading="reconLoading" @click="refreshRecon">刷新</el-button>
            <el-button size="small" type="primary" :loading="reconRunning" @click="triggerRecon">
              立即对账
            </el-button>
            <span class="hint">Redis 低于账单自动修复；高于账单只告警。</span>
          </div>
        </el-card>

        <template v-if="recon">
          <div class="metric-grid">
            <div class="metric">
              <span>最近执行</span>
              <strong class="recon-time">{{ recon.lastRunAt }}</strong>
              <small>状态 <el-tag :type="reconType(recon.status)" size="small">{{ recon.status }}</el-tag></small>
            </div>
            <div class="metric">
              <span>对账广告数</span>
              <strong>{{ recon.adCount }}</strong>
            </div>
            <div class="metric">
              <span>Redis 高于账单</span>
              <strong>{{ recon.alerted }}</strong>
              <small>只告警，不调低 spent</small>
            </div>
            <div class="metric">
              <span>按账单修复</span>
              <strong>{{ recon.repaired }}</strong>
              <small>DB 高于 Redis 时</small>
            </div>
            <div class="metric">
              <span>耗时</span>
              <strong>{{ recon.durationMs }} ms</strong>
            </div>
          </div>

          <el-alert
            v-if="recon.status === 'FAILED'"
            type="error"
            :closable="false"
            show-icon
            :title="recon.message || '对账失败，请查看后端日志'"
            class="formula"
          />

          <el-card class="panel" shadow="never">
            <template #header>差异明细</template>
            <el-table :data="recon.mismatches ?? []" stripe size="small" max-height="360">
              <el-table-column prop="adId" label="adId" width="100" />
              <el-table-column label="方向" width="130">
                <template #default="{ row }">
                  <el-tag :type="directionType(row.direction)" size="small">{{ row.direction }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Redis" width="120">
                <template #default="{ row }">{{ cents(row.redisCents) }}</template>
              </el-table-column>
              <el-table-column label="账单" width="120">
                <template #default="{ row }">{{ cents(row.dbCents) }}</template>
              </el-table-column>
              <el-table-column label="差值" width="120">
                <template #default="{ row }">{{ cents(row.diffCents) }}</template>
              </el-table-column>
              <el-table-column label="处理" width="120">
                <template #default="{ row }">
                  <el-tag :type="actionType(row.action)" size="small">{{ row.action }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-if="!(recon.mismatches?.length)"
              description="本次对账没有超出容差的差异"
              :image-size="60"
            />
          </el-card>
        </template>
        <el-empty v-else description="尚未执行过对账" :image-size="60" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
