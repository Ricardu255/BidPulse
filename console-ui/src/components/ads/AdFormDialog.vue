<script setup lang="ts">
import { computed, ref } from 'vue'
import { watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createAd, updateAd } from '@/api/console'
import type { AdDetail, AdUpsertRequest } from '@/api/types'
import { errorText } from '@/format'
import { useDirtyForm } from '@/composables/useDirtyForm'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{
  editingId: number | null
  editingStatus: number | null
  detail: AdDetail | null
}>()
const emit = defineEmits<{ saved: [] }>()

const saving = ref(false)
const targetOptions = [
  { value: 1, label: '地域' },
  { value: 2, label: '年龄段' },
  { value: 3, label: '性别' },
  { value: 4, label: '兴趣' },
  { value: 5, label: '系统' },
  { value: 6, label: '设备' },
]

function emptyForm(): AdUpsertRequest {
  return {
    advertiserId: 1,
    title: '',
    creativeUrl: 'https://cdn.example.com/creative/new.jpg',
    categoryCode: 'travel',
    bid: 1.0,
    dailyBudget: 1000,
    status: 1,
    targets: [
      { dimType: 1, dimValue: '110000' },
      { dimType: 2, dimValue: '25-30' },
      { dimType: 3, dimValue: 'male' },
    ],
  }
}

const {
  form: adForm,
  dirty: formDirty,
  load: loadForm,
  markSaved: markFormSaved,
} = useDirtyForm<AdUpsertRequest>(emptyForm())

const title = computed(() => {
  const base = props.editingId == null ? '新建广告' : `编辑广告 #${props.editingId}`
  return base + (formDirty.value ? '（未保存）' : '')
})

watch(
  [visible, () => props.detail],
  () => {
    if (!visible.value) return
    if (props.detail) {
      const ad = props.detail.ad
      loadForm({
        advertiserId: ad.advertiserId,
        title: ad.title,
        creativeUrl: ad.creativeUrl,
        categoryCode: ad.categoryCode,
        bid: Number(ad.bid),
        dailyBudget: Number(ad.dailyBudget),
        status: ad.status === 3 ? 2 : ad.status,
        targets: props.detail.targets.map((target) => ({ ...target })),
      })
    } else {
      loadForm(emptyForm())
    }
  },
  { immediate: true },
)

function notifyError(e: unknown): void {
  ElMessage({ message: errorText(e), type: 'error', grouping: true })
}

function addTarget(): void {
  adForm.targets.push({ dimType: 1, dimValue: '' })
}

function removeTarget(index: number): void {
  adForm.targets.splice(index, 1)
}

async function saveAd(): Promise<void> {
  if (!adForm.title.trim() || !adForm.creativeUrl.trim() || !adForm.categoryCode.trim()) {
    ElMessage.error('标题、素材地址、类目不能为空')
    return
  }
  if (adForm.advertiserId == null || adForm.bid == null || adForm.dailyBudget == null) {
    ElMessage.error('广告主、出价、日预算不能为空')
    return
  }
  if (adForm.bid <= 0 || adForm.dailyBudget < 1) {
    ElMessage.error('出价应大于 0，日预算应不小于 1.00 元')
    return
  }
  if (!adForm.targets.length) {
    ElMessage.error('至少需要 1 条定向条件')
    return
  }
  if (adForm.targets.some((target) => !target.dimValue.trim())) {
    ElMessage.error('定向取值不能为空')
    return
  }

  saving.value = true
  try {
    const payload: AdUpsertRequest = {
      ...adForm,
      // 已耗尽广告编辑时不回写 status，避免“只改标题却把系统状态改成在投”
      status: props.editingStatus === 3 ? undefined : adForm.status,
      title: adForm.title.trim(),
      creativeUrl: adForm.creativeUrl.trim(),
      categoryCode: adForm.categoryCode.trim(),
      targets: adForm.targets.map((target) => ({
        dimType: target.dimType,
        dimValue: target.dimValue.trim(),
      })),
    }
    if (props.editingId == null) {
      const result = await createAd(payload)
      ElMessage.success(`已创建广告 #${result.id}`)
    } else {
      await updateAd(props.editingId, payload)
      ElMessage.success(`已更新广告 #${props.editingId}`)
    }
    markFormSaved()
    visible.value = false
    emit('saved')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="680px" destroy-on-close>
    <el-alert
      v-if="editingStatus === 3"
      type="warning"
      :closable="false"
      show-icon
      title="该广告已被系统标记为已耗尽；编辑不会自动恢复投放，请使用列表的“恢复”操作并确认预算已重置。"
      class="formula"
    />
    <el-form :model="adForm" label-position="top">
      <div class="form-grid">
        <el-form-item label="广告主 ID">
          <el-input-number
            v-model="adForm.advertiserId"
            :min="1"
            :disabled="editingId != null"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="类目">
          <el-input v-model="adForm.categoryCode" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="adForm.status">
            <el-option :value="1" label="在投" />
            <el-option :value="2" label="暂停" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="adForm.title" placeholder="创意标题" />
        </el-form-item>
        <el-form-item label="单次出价（元）">
          <el-input-number
            v-model="adForm.bid"
            :min="0.0001"
            :precision="4"
            :step="0.1"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="日预算（元）">
          <el-input-number
            v-model="adForm.dailyBudget"
            :min="1"
            :precision="2"
            :step="100"
            controls-position="right"
          />
        </el-form-item>
      </div>
      <el-form-item label="素材地址">
        <el-input v-model="adForm.creativeUrl" />
      </el-form-item>

      <h4 class="drawer-section">定向条件</h4>
      <div v-for="(target, index) in adForm.targets" :key="index" class="target-row">
        <el-select v-model="target.dimType" style="width: 140px">
          <el-option
            v-for="option in targetOptions"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
        <el-input v-model="target.dimValue" placeholder="取值，如 110000 / 25-30 / male" />
        <el-button link type="danger" @click="removeTarget(index)">删除</el-button>
      </div>
      <el-button size="small" @click="addTarget">添加定向</el-button>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveAd">保存</el-button>
    </template>
  </el-dialog>
</template>
