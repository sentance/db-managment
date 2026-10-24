<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Message from 'primevue/message'
import Skeleton from 'primevue/skeleton'
import MeterBar from '@/components/MeterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import { api, errorMessage } from '@/api/client'
import type { Dashboard } from '@/api/types'
import { ago, bytes } from '@/format'

const data = ref<Dashboard | null>(null)
const error = ref('')
let timer: ReturnType<typeof setInterval>

async function load() {
  try {
    data.value = await api.get<Dashboard>('/api/dashboard')
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}

const severity = (s: string) => (s === 'CRITICAL' ? 'error' : s === 'WARNING' ? 'warn' : 'info')

onMounted(() => {
  load()
  timer = setInterval(load, 15000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>Dashboard</h1><p class="subtitle">Servers, instances and recent operations</p></div>
    </div>
    <Message v-if="error" severity="error">{{ error }}</Message>
    <template v-if="data">
      <div class="grid cols-4" style="margin-bottom: 16px">
        <div class="panel stat"><span class="label">Instances</span><span class="value">{{ data.instances }}</span>
          <span class="hint">{{ Object.entries(data.instancesByStatus).map(([k, v]) => `${v} ${k.toLowerCase()}`).join(' · ') || 'none yet' }}</span></div>
        <div class="panel stat"><span class="label">Managed databases</span><span class="value">{{ data.managedDatabases }}</span></div>
        <div class="panel stat"><span class="label">Warnings</span><span class="value">{{ data.warnings.filter((w) => w.severity !== 'INFO').length }}</span></div>
        <div class="panel stat"><span class="label">Backups</span><span class="value muted" style="font-size: 16px">Phase 2</span><span class="hint">pgBackRest integration is next</span></div>
      </div>

      <div v-if="data.warnings.length" class="panel">
        <h2>Attention</h2>
        <Message v-for="(w, i) in data.warnings" :key="i" :severity="severity(w.severity)" :closable="false" style="margin-bottom: 6px">
          <strong>{{ w.source }}</strong> — {{ w.message }}
        </Message>
      </div>

      <div class="grid cols-2">
        <div v-for="s in data.servers" :key="s.server.id" class="panel">
          <h2>{{ s.server.name }} <span class="muted" style="font-weight: 400">· {{ s.facts?.hostname ?? s.server.hostname }}</span></h2>
          <div v-if="s.metrics" class="form">
            <MeterBar label="CPU" :used="s.metrics.cpuPercent" :total="100" :detail="`${s.metrics.cpuPercent}% · load ${s.metrics.load1}`" />
            <MeterBar label="Memory" :used="s.metrics.memTotalBytes - s.metrics.memAvailableBytes" :total="s.metrics.memTotalBytes"
              :detail="`${bytes(s.metrics.memAvailableBytes)} available of ${bytes(s.metrics.memTotalBytes)}`" />
            <MeterBar v-for="d in s.metrics.disks" :key="d.label" :label="`Disk · ${d.label}`" :used="d.totalBytes - d.freeBytes" :total="d.totalBytes"
              :detail="`${bytes(d.freeBytes)} free of ${bytes(d.totalBytes)}`" />
            <div class="muted">Disk I/O: {{ bytes(s.metrics.diskReadBytesPerSec) }}/s read · {{ bytes(s.metrics.diskWriteBytesPerSec) }}/s write · swap used {{ bytes(s.metrics.swapUsedBytes) }}</div>
          </div>
          <Message v-else severity="error" :closable="false">Agent unreachable: {{ s.error }}</Message>
        </div>
        <div class="panel">
          <h2>Recent operations</h2>
          <DataTable :value="data.recentJobs" size="small">
            <Column field="title" header="Operation" />
            <Column header="Status"><template #body="{ data: j }"><StatusTag :status="j.status" /></template></Column>
            <Column header="When"><template #body="{ data: j }">{{ ago(j.createdAt) }}</template></Column>
          </DataTable>
          <RouterLink to="/activity">All activity →</RouterLink>
        </div>
      </div>
    </template>
    <div v-else-if="!error" class="grid cols-4"><Skeleton v-for="i in 4" :key="i" height="96px" /></div>
  </div>
</template>
