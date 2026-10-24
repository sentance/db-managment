<script setup lang="ts">
import Tag from 'primevue/tag'
import { statusSeverity } from '@/format'

defineProps<{ status: string }>()
</script>

<template>
  <Tag :value="status" :severity="statusSeverity(status)" />
</template>
