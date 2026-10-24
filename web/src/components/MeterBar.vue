<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ label: string; used: number; total: number; detail?: string }>()
const pct = computed(() => (props.total > 0 ? Math.min(100, (props.used / props.total) * 100) : 0))
const level = computed(() => (pct.value >= 90 ? 'danger' : pct.value >= 80 ? 'warn' : ''))
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between">
      <span>{{ label }}</span>
      <span class="muted">{{ detail ?? pct.toFixed(0) + '%' }}</span>
    </div>
    <div class="meter" :class="level"><span :style="{ width: pct + '%' }" /></div>
  </div>
</template>
