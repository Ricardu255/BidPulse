<script setup lang="ts">
defineProps<{
  title: string
  description: string
  tag?: string
}>()
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <slot name="actions">
      <el-tag v-if="tag" type="info" effect="plain">{{ tag }}</el-tag>
    </slot>
  </header>
</template>
