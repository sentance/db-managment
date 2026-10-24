<script setup lang="ts">
import { watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import ProgressBar from 'primevue/progressbar'
import Message from 'primevue/message'
import StatusTag from './StatusTag.vue'
import { useJob } from '@/composables/useJob'
import { dateTime } from '@/format'
import type { Job } from '@/api/types'

const props = defineProps<{ jobId: string | null }>()
const emit = defineEmits<{ close: []; done: [job: Job] }>()
const { job, events, watch: watchJob, stop } = useJob()

watch(
  () => props.jobId,
  (id) => (id ? watchJob(id, (j) => emit('done', j)) : stop()),
  { immediate: true },
)
</script>

<template>
  <Dialog :visible="jobId !== null" modal :header="job?.title ?? 'Operation'" :style="{ width: '640px' }"
    @update:visible="(v) => !v && emit('close')">
    <div v-if="job" class="form">
      <div style="display: flex; gap: 10px; align-items: center">
        <StatusTag :status="job.status" />
        <span class="muted">{{ job.message }}</span>
      </div>
      <ProgressBar :value="job.progress" :show-value="true" style="height: 14px" />
      <Message v-if="job.error" severity="error" :closable="false"><pre style="margin: 0; white-space: pre-wrap">{{ job.error }}</pre></Message>
      <div class="logs" style="max-height: 260px">
        <div v-for="e in events" :key="e.id">{{ dateTime(e.ts) }} [{{ e.level }}] {{ e.message }}</div>
      </div>
    </div>
    <div v-else class="empty">Loading…</div>
    <template #footer>
      <Button :label="job && (job.status === 'SUCCEEDED' || job.status === 'FAILED') ? 'Close' : 'Run in background'" text
        @click="emit('close')" />
    </template>
  </Dialog>
</template>
