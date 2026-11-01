<script setup lang="ts">
import { ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import OpsGroupsPanel from '@/components/ops/OpsGroupsPanel.vue'
import DltPanel from '@/components/ops/DltPanel.vue'
import ReconPanel from '@/components/ops/ReconPanel.vue'

const activeTab = ref<'groups' | 'dlt' | 'recon'>('groups')
</script>

<template>
  <div class="page">
    <PageHeader
      title="运行监控"
      description="看异步链路有没有卡住：消费积压、失败消息重放、Redis 与账单差异。"
      tag="GET /console-api/v1/kafka/groups"
    />

    <el-tabs v-model="activeTab" class="ops-tabs">
      <el-tab-pane label="消费组" name="groups">
        <OpsGroupsPanel :active="activeTab === 'groups'" />
      </el-tab-pane>
      <el-tab-pane label="失败消息重放" name="dlt">
        <DltPanel />
      </el-tab-pane>
      <el-tab-pane label="预算对账" name="recon">
        <ReconPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
