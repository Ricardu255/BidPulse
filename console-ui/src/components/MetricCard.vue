<script setup lang="ts">
defineProps<{
  label: string
  value?: string | number
  hint?: string
}>()
</script>

<template>
  <div class="metric">
    <span>{{ label }}</span>
    <slot name="value">
      <strong>{{ value }}</strong>
    </slot>
    <slot name="hint">
      <small v-if="hint">{{ hint }}</small>
    </slot>
  </div>
</template>
