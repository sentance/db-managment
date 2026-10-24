<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import StatusTag from '@/components/StatusTag.vue'
import JobDialog from '@/components/JobDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { AuditEntry, Job } from '@/api/types'
import { ago, dateTime } from '@/format'

const jobs = ref<Job[]>([])
const audit = ref<AuditEntry[]>([])
const verify = ref<{ intact: boolean; checked: number; firstBrokenId: number | null } | null>(null)
const error = ref('')
const selectedJob = ref<string | null>(null)
let timer: ReturnType<typeof setInterval>

async function loadJobs() {
  try {
    jobs.value = await api.get<Job[]>('/api/jobs?limit=200')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function loadAudit() {
  try {
    audit.value = await api.get<AuditEntry[]>('/api/audit?limit=300')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function verifyChain() {
  verify.value = await api.get('/api/audit/verify')
}

onMounted(() => {
  loadJobs()
  loadAudit()
  timer = setInterval(loadJobs, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="page">
    <div class="page-header"><div><h1>Activity & Jobs</h1><p class="subtitle">Background operations and the tamper-evident audit log.</p></div></div>
    <Message v-if="error" severity="error">{{ error }}</Message>
    <Tabs value="jobs">
      <TabList><Tab value="jobs">Jobs</Tab><Tab value="audit">Audit log</Tab></TabList>
      <TabPanels style="padding: 16px 0 0; background: transparent">
        <TabPanel value="jobs">
          <div class="panel">
            <DataTable :value="jobs" size="small" row-hover style="cursor: pointer" paginator :rows="25" @row-click="(e) => (selectedJob = e.data.id)">
              <Column field="title" header="Operation" />
              <Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column>
              <Column header="Progress" style="width: 160px"><template #body="{ data }"><ProgressBar :value="data.progress" :show-value="false" style="height: 6px" /></template></Column>
              <Column header="Message"><template #body="{ data }"><span :class="data.error ? '' : 'muted'" :style="data.error ? 'color: var(--app-danger)' : ''">{{ data.error ?? data.message }}</span></template></Column>
              <Column field="createdBy" header="By" />
              <Column header="Created"><template #body="{ data }">{{ ago(data.createdAt) }}</template></Column>
            </DataTable>
          </div>
        </TabPanel>
        <TabPanel value="audit">
          <div class="panel">
            <div class="actions" style="margin-bottom: 10px">
              <Button label="Verify hash chain" icon="pi pi-verified" size="small" outlined @click="verifyChain" />
              <span v-if="verify" :style="{ color: verify.intact ? 'var(--app-accent)' : 'var(--app-danger)' }">
                {{ verify.intact ? `Intact: ${verify.checked} entries verified` : `Broken at entry #${verify.firstBrokenId}` }}
              </span>
            </div>
            <DataTable :value="audit" size="small" paginator :rows="50">
              <Column header="Time"><template #body="{ data }">{{ dateTime(data.ts) }}</template></Column>
              <Column field="actor" header="Actor" />
              <Column field="action" header="Action"><template #body="{ data }"><code>{{ data.action }}</code></template></Column>
              <Column header="Target"><template #body="{ data }"><span class="mono">{{ data.targetType }} {{ data.targetId }}</span></template></Column>
              <Column header="Outcome"><template #body="{ data }"><StatusTag :status="data.outcome" /></template></Column>
              <Column header="Details"><template #body="{ data }"><span class="mono muted">{{ Object.keys(data.details).length ? JSON.stringify(data.details) : '' }}</span></template></Column>
              <Column field="sourceIp" header="IP" />
            </DataTable>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
    <JobDialog :job-id="selectedJob" @close="selectedJob = null" />
  </div>
</template>
