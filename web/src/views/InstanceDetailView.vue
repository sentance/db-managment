<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import StatusTag from '@/components/StatusTag.vue'
import JobDialog from '@/components/JobDialog.vue'
import ConfirmNameDialog from '@/components/ConfirmNameDialog.vue'
import DatabasesTable from '@/components/DatabasesTable.vue'
import RolesPanel from '@/components/RolesPanel.vue'
import { api, errorMessage } from '@/api/client'
import type { BlockedSession, ExternalMode, Instance, Overview, Protection, Session, Submitted } from '@/api/types'
import { ago, bytes, dateTime, duration } from '@/format'

const props = defineProps<{ id: string }>()
const router = useRouter()
const toast = useToast()
const instance = ref<Instance | null>(null)
const overview = ref<Overview | null>(null)
const overviewError = ref('')
const sessions = ref<Session[]>([])
const blocked = ref<BlockedSession[]>([])
const logs = ref<string[]>([])
const logsError = ref('')
const jobId = ref<string | null>(null)
const tab = ref('overview')
const deleting = ref(false)
const deleteVolume = ref(false)
const protection = ref<Protection>('STANDARD')
const mode = ref<ExternalMode>('MONITOR_ONLY')
let timer: ReturnType<typeof setInterval>

const isManaged = computed(() => instance.value?.kind === 'MANAGED')

async function load() {
  try {
    instance.value = await api.get<Instance>(`/api/instances/${props.id}`)
    protection.value = instance.value.protection
    if (instance.value.externalMode) mode.value = instance.value.externalMode
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot load instance', detail: errorMessage(e), life: 6000 })
  }
  loadOverview()
}

async function loadOverview() {
  try {
    overview.value = await api.get<Overview>(`/api/instances/${props.id}/monitoring`)
    overviewError.value = ''
  } catch (e) {
    overview.value = null
    overviewError.value = errorMessage(e)
  }
}

async function loadActivity() {
  try {
    ;[sessions.value, blocked.value] = await Promise.all([
      api.get<Session[]>(`/api/instances/${props.id}/monitoring/activity`),
      api.get<BlockedSession[]>(`/api/instances/${props.id}/monitoring/locks`),
    ])
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot load activity', detail: errorMessage(e), life: 5000 })
  }
}

async function loadLogs() {
  try {
    logs.value = (await api.get<{ lines: string[] }>(`/api/instances/${props.id}/logs?tail=400`)).lines
    logsError.value = ''
  } catch (e) {
    logsError.value = errorMessage(e)
  }
}

async function action(a: 'START' | 'STOP' | 'RESTART') {
  try {
    jobId.value = (await api.post<Submitted>(`/api/instances/${props.id}/actions`, { action: a })).jobId
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Action failed', detail: errorMessage(e), life: 6000 })
  }
}

async function saveProtection() {
  try {
    instance.value = await api.put<Instance>(`/api/instances/${props.id}/protection`, { protection: protection.value })
    toast.add({ severity: 'success', summary: 'Protection updated', life: 2500 })
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Update failed', detail: errorMessage(e), life: 6000 })
  }
}

async function saveMode() {
  try {
    instance.value = await api.put<Instance>(`/api/instances/${props.id}/external-mode`, { mode: mode.value })
    toast.add({ severity: 'success', summary: 'Mode updated', life: 2500 })
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Update failed', detail: errorMessage(e), life: 6000 })
  }
}

async function remove(name: string) {
  try {
    const r = await api.post<Submitted>(`/api/instances/${props.id}/delete`, { confirmName: name, deleteVolume: deleteVolume.value })
    deleting.value = false
    if (r.jobId) jobId.value = r.jobId
    else router.push({ name: 'instances' })
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Delete failed', detail: errorMessage(e), life: 7000 })
  }
}

function onTab(v: string | number) {
  tab.value = String(v)
  if (tab.value === 'activity') loadActivity()
  if (tab.value === 'logs') loadLogs()
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    if (tab.value === 'overview') load()
    if (tab.value === 'activity') loadActivity()
  }, 10000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div v-if="instance" class="page">
    <div class="page-header">
      <div>
        <h1>{{ instance.name }} <StatusTag :status="instance.status" /></h1>
        <p class="subtitle">
          {{ instance.kind === 'MANAGED' ? 'Managed instance' : 'External instance' }} · PostgreSQL {{ instance.pgMajor ?? '?' }}
          <Tag :value="instance.protection" :severity="instance.protection === 'PRODUCTION' ? 'danger' : 'secondary'" style="margin-left: 6px" />
          <Tag v-if="instance.externalMode" :value="instance.externalMode" severity="secondary" style="margin-left: 4px" />
        </p>
        <p v-if="instance.statusMessage" class="muted" style="margin: 4px 0 0">{{ instance.statusMessage }}</p>
      </div>
      <div v-if="isManaged" class="actions">
        <Button label="Start" icon="pi pi-play" outlined size="small" :disabled="instance.status === 'RUNNING'" @click="action('START')" />
        <Button label="Restart" icon="pi pi-replay" outlined size="small" @click="action('RESTART')" />
        <Button label="Stop" icon="pi pi-stop" outlined severity="warn" size="small" :disabled="instance.status === 'STOPPED'" @click="action('STOP')" />
      </div>
    </div>

    <Tabs :value="tab" @update:value="onTab">
      <TabList>
        <Tab value="overview">Overview</Tab>
        <Tab value="databases">Databases</Tab>
        <Tab value="roles">Users & permissions</Tab>
        <Tab value="activity">Activity</Tab>
        <Tab v-if="instance.containerName" value="logs">Logs</Tab>
        <Tab value="settings">Settings</Tab>
      </TabList>
      <TabPanels style="padding: 16px 0 0; background: transparent">
        <TabPanel value="overview">
          <div class="grid cols-2">
            <div class="panel">
              <h2>Instance</h2>
              <dl class="kv">
                <dt>Version</dt><dd>{{ instance.pgVersion ?? '—' }}</dd>
                <dt>Image</dt><dd class="mono">{{ instance.image ?? '—' }}</dd>
                <dt>libc</dt><dd>{{ instance.libc ?? 'unknown' }}</dd>
                <dt>Container</dt><dd class="mono">{{ instance.containerName ?? '—' }}</dd>
                <dt>Endpoint</dt><dd class="mono">{{ instance.host }}:{{ instance.port }} · sslmode={{ instance.sslMode }}</dd>
                <dt v-if="instance.hostPort">Published</dt><dd v-if="instance.hostPort" class="mono">{{ instance.bindAddress }}:{{ instance.hostPort }}</dd>
                <dt v-if="isManaged">Networks</dt><dd v-if="isManaged" class="mono">pgcp-instances{{ instance.extraNetworks.length ? ', ' + instance.extraNetworks.join(', ') : '' }}</dd>
                <dt v-if="isManaged">Resources</dt><dd v-if="isManaged">{{ instance.memoryMb }} MB · {{ instance.cpus }} CPU</dd>
                <dt>Admin role</dt><dd class="mono">{{ instance.adminRole }}</dd>
                <dt>Created</dt><dd>{{ dateTime(instance.createdAt) }}</dd>
              </dl>
            </div>
            <div class="panel">
              <h2>Health</h2>
              <Message v-if="overviewError" severity="error" :closable="false">{{ overviewError }}</Message>
              <template v-if="overview">
                <div class="grid cols-3">
                  <div class="stat"><span class="label">Connections</span><span class="value">{{ overview.connections }}<span class="muted" style="font-size: 14px"> / {{ overview.maxConnections }}</span></span>
                    <span class="hint">{{ Object.entries(overview.connectionsByState).map(([k, v]) => `${v} ${k}`).join(' · ') }}</span></div>
                  <div class="stat"><span class="label">Long queries</span><span class="value">{{ overview.longRunningQueries }}</span><span class="hint">active &gt; 60s</span></div>
                  <div class="stat"><span class="label">Blocked</span><span class="value">{{ overview.blockedSessions }}</span><span class="hint">waiting on locks</span></div>
                </div>
                <dl class="kv" style="margin-top: 14px">
                  <dt>Uptime</dt><dd>{{ duration(overview.uptimeSeconds) }}</dd>
                  <dt>Replicas</dt><dd>{{ overview.replicas }}</dd>
                  <dt>WAL archiving</dt><dd>archive_mode={{ overview.archiver.archiveMode }} · {{ overview.archiver.archivedCount }} archived,
                    last {{ ago(overview.archiver.lastArchivedAt) }}<span v-if="overview.archiver.failedCount"> · <strong style="color: var(--app-danger)">{{ overview.archiver.failedCount }} failed</strong>, last {{ ago(overview.archiver.lastFailedAt) }}</span></dd>
                </dl>
              </template>
            </div>
          </div>
          <div v-if="overview" class="panel">
            <h2>Transaction activity</h2>
            <DataTable :value="overview.databases" size="small">
              <Column field="database" header="Database" />
              <Column field="backends" header="Sessions" />
              <Column field="commits" header="Commits" />
              <Column field="rollbacks" header="Rollbacks" />
              <Column header="Cache hit"><template #body="{ data }">{{ (data.cacheHitRatio * 100).toFixed(1) }}%</template></Column>
              <Column header="Rows ins/upd/del"><template #body="{ data }">{{ data.tuplesInserted }} / {{ data.tuplesUpdated }} / {{ data.tuplesDeleted }}</template></Column>
              <Column field="deadlocks" header="Deadlocks" />
              <Column header="Temp"><template #body="{ data }">{{ bytes(data.tempBytes) }}</template></Column>
            </DataTable>
          </div>
        </TabPanel>

        <TabPanel value="databases"><DatabasesTable :instance="instance" /></TabPanel>
        <TabPanel value="roles"><RolesPanel :instance="instance" /></TabPanel>

        <TabPanel value="activity">
          <div class="panel">
            <div style="display: flex; justify-content: space-between"><h2>Sessions</h2><Button icon="pi pi-refresh" text @click="loadActivity" /></div>
            <DataTable :value="sessions" size="small" scrollable>
              <Column field="pid" header="PID" />
              <Column header="User / DB"><template #body="{ data }">{{ data.user }} <span class="muted">→ {{ data.database }}</span></template></Column>
              <Column header="Client"><template #body="{ data }"><span class="muted">{{ data.application || '—' }} {{ data.client ?? '' }}</span></template></Column>
              <Column header="State"><template #body="{ data }">{{ data.state }}<div v-if="data.waitEvent" class="muted">{{ data.waitEventType }}: {{ data.waitEvent }}</div></template></Column>
              <Column header="Running"><template #body="{ data }">{{ duration(data.querySeconds) }}</template></Column>
              <Column header="Query"><template #body="{ data }"><code style="white-space: pre-wrap">{{ data.query }}</code></template></Column>
            </DataTable>
          </div>
          <div class="panel">
            <h2>Lock waits</h2>
            <DataTable :value="blocked" size="small">
              <Column field="pid" header="PID" />
              <Column header="Blocked by"><template #body="{ data }">{{ data.blockedBy.join(', ') }}</template></Column>
              <Column header="User / DB"><template #body="{ data }">{{ data.user }} → {{ data.database }}</template></Column>
              <Column header="Waiting"><template #body="{ data }">{{ duration(data.seconds) }}</template></Column>
              <Column header="Query"><template #body="{ data }"><code>{{ data.query }}</code></template></Column>
              <template #empty><div class="empty">No sessions are waiting on locks.</div></template>
            </DataTable>
          </div>
        </TabPanel>

        <TabPanel value="logs">
          <div class="panel">
            <div style="display: flex; justify-content: space-between"><h2>Container log</h2><Button icon="pi pi-refresh" text @click="loadLogs" /></div>
            <Message v-if="logsError" severity="error" :closable="false">{{ logsError }}</Message>
            <div class="logs">{{ logs.join('\n') }}</div>
          </div>
        </TabPanel>

        <TabPanel value="settings">
          <div class="panel">
            <h2>Protection</h2>
            <p class="muted">PRODUCTION instances cannot be deleted, and stopping, restarting or relaxing protection requires authenticator confirmation.</p>
            <div class="actions"><Select v-model="protection" :options="['PRODUCTION', 'STANDARD', 'EPHEMERAL']" /><Button label="Save" size="small" @click="saveProtection" /></div>
          </div>
          <div v-if="!isManaged" class="panel">
            <h2>Management mode</h2>
            <p class="muted">MONITOR_ONLY never changes anything. MANAGE_OBJECTS allows creating databases and roles; server configuration is never touched.</p>
            <div class="actions"><Select v-model="mode" :options="['MONITOR_ONLY', 'MANAGE_OBJECTS']" /><Button label="Save" size="small" @click="saveMode" /></div>
          </div>
          <div v-if="isManaged && Object.keys(instance.settings).length" class="panel">
            <h2>PostgreSQL parameters</h2>
            <dl class="kv"><template v-for="(v, k) in instance.settings" :key="k"><dt class="mono">{{ k }}</dt><dd class="mono">{{ v }}</dd></template></dl>
          </div>
          <div class="panel danger-zone">
            <h2>{{ isManaged ? 'Delete instance' : 'Unregister instance' }}</h2>
            <p v-if="isManaged">Stops and removes the container. The data volume is kept unless you choose to delete it.</p>
            <p v-else>Removes the instance from pgcp only. The PostgreSQL server itself is not changed.</p>
            <Button :label="isManaged ? 'Delete…' : 'Unregister…'" severity="danger" outlined :disabled="instance.protection === 'PRODUCTION'" @click="deleting = true" />
            <small v-if="instance.protection === 'PRODUCTION'" class="muted" style="margin-left: 8px">Change protection from PRODUCTION first.</small>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <ConfirmNameDialog :visible="deleting" :title="`${isManaged ? 'Delete' : 'Unregister'} ${instance.name}`" :name="instance.name"
      :confirm-label="isManaged ? 'Delete instance' : 'Unregister'" @cancel="deleting = false" @confirm="remove">
      <div v-if="isManaged" class="check"><Checkbox v-model="deleteVolume" binary input-id="dv" />
        <label for="dv">Also delete the data volume <strong>(irreversible)</strong></label></div>
    </ConfirmNameDialog>
    <JobDialog :job-id="jobId" @close="jobId = null" @done="(j) => (j.type === 'instance.delete' && j.status === 'SUCCEEDED' ? router.push({ name: 'instances' }) : load())" />
  </div>
</template>
