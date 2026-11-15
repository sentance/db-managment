<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Select from 'primevue/select'
import Message from 'primevue/message'
import Checkbox from 'primevue/checkbox'
import InputNumber from 'primevue/inputnumber'
import CopyButton from './CopyButton.vue'
import JobDialog from './JobDialog.vue'
import StatusTag from './StatusTag.vue'
import BackupAlertsPanel from './BackupAlertsPanel.vue'
import { api, errorMessage } from '@/api/client'
import type { BackupDestination, LogicalBackup, LogicalRestore, Instance, DatabaseView, ServerStatus, DiscoveredContainer } from '@/api/types'
import { bytes, dateTime } from '@/format'

defineProps<{ restoreOnly?: boolean }>()
const destinations = ref<BackupDestination[]>([]), backups = ref<LogicalBackup[]>([]), restores = ref<LogicalRestore[]>([]), instances = ref<Instance[]>([])
const error = ref(''), formError = ref(''), busy = ref(false), jobId = ref<string | null>(null)
const mode = ref<'storage' | 'backup' | 'restore' | 'policy' | null>(null), recoveryIdentity = ref<string | null>(null)
interface Policy {
  id: string; name: string; revision: number; instanceId: string; database: string; destinationId: string; containerId: string
  dailyTime: string; timezone: string; enabled: boolean; maxAgeHours: number; syncState: string; health: string; keepLast: number; keepDays: number
  execution: { enabled: boolean; nextRunAt: string; lastSuccessAt: string | null; activeRunId: string | null; retentionHealth: string } | null
}
const policies = ref<Policy[]>([]), editingPolicy = ref<string | null>(null)
const retentionEnabled = ref(false)
const schedule = reactive({ name: '', dailyTime: '02:00', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', enabled: true, runImmediately: false, maxAgeHours: 26, keepLast: 7, keepDays: 30 })
const healthLabels: Record<string, string> = { UNKNOWN: 'Unknown', SYNC_PENDING: 'Waiting for agent', AWAITING_FIRST_BACKUP: 'Awaiting first backup', OVERDUE: 'Overdue', BACKUP_FAILED: 'Backup failed', STALE: 'Backup is stale', HEALTHY: 'Healthy', PAUSED: 'Paused' }
const syncLabels: Record<string, string> = { PENDING: 'Pending', APPLIED: 'Confirmed', UNCONFIRMED: 'Unconfirmed' }
const destination = reactive({ name: '', endpoint: '', region: 'auto', bucket: '', accessKey: '', secretKey: '' })
const sourceId = ref<string | null>(null), database = ref<string | null>(null), destinationId = ref<string | null>(null)
const databases = ref<DatabaseView[]>([]), container = ref<DiscoveredContainer['container'] | null>(null)
const backupId = ref(''), restore = reactive({ instanceName: '', database: 'restored', owner: 'restored_owner' })
const verified = computed(() => destinations.value.filter(d => d.status === 'VERIFIED'))
let timer: ReturnType<typeof setInterval>
async function load() {
  try {
    const values = await Promise.all([api.get<BackupDestination[]>('/api/backups/destinations'), api.get<LogicalBackup[]>('/api/backups/logical'), api.get<LogicalRestore[]>('/api/backups/restores'), api.get<Instance[]>('/api/instances'), api.get<Policy[]>('/api/backups/policies')])
    ;[destinations.value, backups.value, restores.value, instances.value, policies.value] = values
    error.value = ''
  } catch (e) { error.value = errorMessage(e) }
}
function open(next: typeof mode.value) { mode.value = next; formError.value = '' }
async function editPolicy(policy?: Policy) {
  editingPolicy.value = policy?.id ?? null
  Object.assign(schedule, { name: policy?.name ?? '', dailyTime: policy?.dailyTime ?? '02:00', timezone: policy?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC', enabled: policy?.enabled ?? true, runImmediately: false, maxAgeHours: policy?.maxAgeHours ?? 26 })
  retentionEnabled.value = (policy?.keepLast ?? 0) > 0; schedule.keepLast = policy?.keepLast || 7; schedule.keepDays = policy?.keepDays || 30
  sourceId.value = policy?.instanceId ?? null; destinationId.value = policy?.destinationId ?? null
  database.value = null; databases.value = []; container.value = null; open('policy')
  if (policy) { await selectSource(); database.value = policy.database }
}
async function togglePolicy(policy: Policy) {
  try { await api.put(`/api/backups/policies/${policy.id}/enabled`, { enabled: !policy.enabled }); await load() }
  catch (e) { error.value = errorMessage(e) }
}
async function selectSource() {
  database.value = null; databases.value = []; container.value = null; formError.value = ''; busy.value = true
  const id = sourceId.value
  try {
    if (!id) return
    const source = instances.value.find(i => i.id === id)!
    const dbs = await api.get<DatabaseView[]>(`/api/instances/${id}/databases`)
    if (sourceId.value !== id) return
    databases.value = dbs
    for (const server of await api.get<ServerStatus[]>('/api/servers')) {
      if (!server.agentReachable) continue
      const found = await api.get<DiscoveredContainer[]>(`/api/servers/${server.server.id}/containers`)
      if (sourceId.value !== id) return
      container.value = found.find(c => c.container.name === source.containerName)?.container ?? null
      if (container.value) break
    }
    if (!container.value) formError.value = 'The registered source container is unavailable.'
  } catch (e) { formError.value = errorMessage(e) }
  finally { busy.value = false }
}
async function submit() {
  busy.value = true; formError.value = ''
  try {
    if (mode.value === 'storage') {
      const result = await api.post<{ destination: BackupDestination; recoveryIdentity: string }>('/api/backups/destinations', destination)
      recoveryIdentity.value = result.recoveryIdentity; destination.accessKey = ''; destination.secretKey = ''
    } else if (mode.value === 'backup') {
      jobId.value = (await api.post<{ jobId: string }>(`/api/backups/logical/${sourceId.value}`, { database: database.value, destinationId: destinationId.value, containerId: container.value?.id })).jobId
    } else if (mode.value === 'policy') {
      const body = { ...schedule, keepLast: retentionEnabled.value ? schedule.keepLast : 0, keepDays: retentionEnabled.value ? schedule.keepDays : 0, instanceId: sourceId.value, database: database.value, destinationId: destinationId.value, containerId: container.value?.id }
      if (editingPolicy.value) await api.put(`/api/backups/policies/${editingPolicy.value}`, body)
      else await api.post('/api/backups/policies', body)
    } else {
      jobId.value = (await api.post<{ jobId: string }>(`/api/backups/logical/${backupId.value}/restore`, restore)).jobId
    }
    mode.value = null; await load()
  } catch (e) { formError.value = errorMessage(e) }
  finally { busy.value = false }
}
async function test(id: string) {
  try { jobId.value = (await api.post<{ jobId: string }>(`/api/backups/destinations/${id}/test`)).jobId }
  catch (e) { error.value = errorMessage(e) }
}
onMounted(() => { load(); timer = setInterval(load, 10000) })
onUnmounted(() => { clearInterval(timer); recoveryIdentity.value = null })
</script>

<template>
  <div id="logical">
    <Message v-if="error" severity="error">{{ error }}</Message>
    <BackupAlertsPanel v-if="!restoreOnly" />
    <div v-if="!restoreOnly" class="panel">
      <div class="page-header"><h2>Encrypted database backups</h2><div class="actions"><Button label="Add storage" outlined size="small" @click="open('storage')" /><Button label="Back up a database" size="small" :disabled="!verified.length" @click="open('backup')" /></div></div>
      <p class="muted">Exports are encrypted before upload to your S3-compatible storage.</p>
      <DataTable :value="destinations" size="small"><Column field="name" header="Destination" /><Column field="bucket" header="Bucket" /><Column field="prefix" header="Owned prefix" /><Column header="Connection"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column><Column header=""><template #body="{ data }"><Button label="Test read / write" text size="small" @click="test(data.id)" /></template></Column><template #empty>Add storage, save its recovery key, and test the connection.</template></DataTable>
    </div>
    <div v-if="!restoreOnly" class="panel">
      <div class="page-header"><h2>Daily backup schedules</h2><Button label="Schedule backups" size="small" :disabled="!verified.length" @click="editPolicy()" /></div>
      <p class="muted">Schedules and enabled retention run on the agent while the panel is offline. Pausing takes effect when the agent confirms it. An active backup or deletion finishes.</p>
      <DataTable :value="policies" size="small" data-key="id">
        <Column field="name" header="Policy" /><Column field="database" header="Database" />
        <Column header="Daily time"><template #body="{ data }">{{ data.dailyTime }} · {{ data.timezone }}</template></Column>
        <Column header="Agent sync"><template #body="{ data }">{{ syncLabels[data.syncState] ?? data.syncState }}</template></Column><Column header="Health"><template #body="{ data }">{{ healthLabels[data.health] ?? data.health }}<span v-if="data.execution?.activeRunId"> · Run in progress</span></template></Column>
        <Column header="Next run"><template #body="{ data }">{{ data.execution?.enabled ? dateTime(data.execution.nextRunAt) : 'Paused or pending' }}</template></Column>
        <Column header="Last successful backup"><template #body="{ data }">{{ data.execution?.lastSuccessAt ? dateTime(data.execution.lastSuccessAt) : 'None confirmed' }}</template></Column>
        <Column header="Retention"><template #body="{ data }">{{ data.keepLast ? `Keep ${data.keepLast} and ${data.keepDays} days` : 'Keep all' }}<span v-if="data.execution?.retentionHealth === 'ERROR'"> · Cleanup failed</span></template></Column>
        <Column header=""><template #body="{ data }"><div class="actions"><Button :label="data.enabled ? 'Pause' : 'Resume'" text size="small" @click="togglePolicy(data)" /><Button label="Edit / refresh access" text size="small" @click="editPolicy(data)" /></div></template></Column>
        <template #empty>No schedules. Add one to allow recurring database exports.</template>
      </DataTable>
    </div>
    <div class="panel"><h2>Saved logical backups</h2>
      <DataTable :value="backups" size="small" paginator :rows="10" data-key="id">
        <Column header="Source"><template #body="{ data }">{{ instances.find(i => i.id === data.instanceId)?.name ?? data.instanceId }}</template></Column>
        <Column field="databaseName" header="Database" /><Column header="Created"><template #body="{ data }">{{ dateTime(data.createdAt) }}</template></Column><Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column>
        <Column header="Encrypted size"><template #body="{ data }">{{ bytes(data.result?.sizeBytes ? Number(data.result.sizeBytes) : null) }}</template></Column><Column header="Last verified restore"><template #body="{ data }">{{ data.verifiedAt ? dateTime(data.verifiedAt) : 'Never verified' }}</template></Column>
        <Column header=""><template #body="{ data }"><div class="actions"><Button v-if="data.jobId" label="Job" text size="small" @click="jobId = data.jobId" /><span v-else class="muted">Scheduled</span><Button label="Restore to new instance" text size="small" :disabled="data.status !== 'SUCCEEDED'" @click="backupId = data.id; restore.instanceName = ''; open('restore')" /></div></template></Column><template #empty>No logical backups have been created yet.</template>
      </DataTable>
    </div>
    <div class="panel"><h2>Restore history</h2><p class="muted">Destinations stay on a separate internal network with no published ports. Applications keep using their original databases.</p>
      <DataTable :value="restores" size="small" paginator :rows="10" data-key="id"><Column field="targetName" header="New instance" /><Column field="databaseName" header="Database" /><Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column><Column header="Checks"><template #body="{ data }">{{ data.result?.verified === 'true' ? `${data.result.tablesChecked} tables · ${data.result.rowsChecked} rows` : 'Not verified' }}</template></Column><Column header=""><template #body="{ data }"><Button label="Job" text size="small" @click="jobId = data.jobId" /><RouterLink v-if="data.targetInstanceId" :to="`/instances/${data.targetInstanceId}`">Open instance</RouterLink></template></Column></DataTable>
    </div>
    <Dialog :visible="mode !== null" modal :header="mode === 'storage' ? 'Add S3-compatible storage' : mode === 'backup' ? 'Back up a database' : mode === 'policy' ? 'Schedule daily backups' : 'Restore into a new isolated instance'" :style="{ width: 'min(600px, 95vw)' }" @update:visible="v => !v && (mode = null)">
      <form class="form" @submit.prevent="submit">
        <template v-if="mode === 'storage'">
          <div class="field"><label>Name</label><InputText v-model="destination.name" required maxlength="100" /></div><div class="field"><label>HTTPS endpoint (empty for AWS S3)</label><InputText v-model="destination.endpoint" /></div>
          <div class="field-row"><div class="field"><label>Region</label><InputText v-model="destination.region" required /></div><div class="field"><label>Existing bucket</label><InputText v-model="destination.bucket" required /></div></div>
          <div class="field"><label>Access key</label><InputText v-model="destination.accessKey" required autocomplete="off" /></div><div class="field"><label>Secret key</label><Password v-model="destination.secretKey" required :feedback="false" toggle-mask autocomplete="new-password" /></div><p class="muted">A new owned prefix and encryption key will be created. Save the recovery key outside this server when it is displayed.</p>
        </template>
        <template v-else-if="mode === 'backup' || mode === 'policy'">
          <div class="field"><label>Instance</label><Select v-model="sourceId" :options="instances.filter(i => i.containerName)" option-label="name" option-value="id" :disabled="mode === 'policy' && !!editingPolicy" @change="selectSource" /></div><div class="field"><label>Database</label><Select v-model="database" :options="databases.filter(d => !d.system)" option-label="name" option-value="name" :disabled="mode === 'policy' && !!editingPolicy" /></div><div class="field"><label>Storage</label><Select v-model="destinationId" :options="verified" option-label="name" option-value="id" :disabled="mode === 'policy' && !!editingPolicy" /></div>
          <p v-if="container" class="muted">Source: {{ container.name }} · {{ container.id.slice(0, 12) }}</p><p class="muted">Export uses the registered login and reads all tables for recovery checks. It does not grant privileges or change source configuration.</p>
          <template v-if="mode === 'policy'">
            <div class="field"><label>Policy name</label><InputText v-model="schedule.name" required maxlength="100" /></div>
            <div class="field-row"><div class="field"><label>Daily time</label><InputText v-model="schedule.dailyTime" type="time" required /></div><div class="field"><label>Timezone</label><InputText v-model="schedule.timezone" required placeholder="Europe/Prague" /></div></div>
            <div class="field"><label>Report stale after (hours)</label><InputNumber v-model="schedule.maxAgeHours" :min="24" :max="8760" :use-grouping="false" /></div>
            <label><Checkbox v-model="retentionEnabled" binary /> Automatically expire older backups from this schedule</label>
            <div v-if="retentionEnabled" class="field-row"><div class="field"><label>Keep at least this many backups</label><InputNumber v-model="schedule.keepLast" :min="2" :max="3650" :use-grouping="false" /></div><div class="field"><label>Keep all backups younger than (days)</label><InputNumber v-model="schedule.keepDays" :min="1" :max="3650" :use-grouping="false" /></div></div>
            <p v-if="retentionEnabled" class="muted">The latest verified recovery point and backups reserved by restores are also protected. Manual exports and existing external repositories keep their current retention.</p>
            <label><Checkbox v-model="schedule.enabled" binary /> Enable recurring exports</label>
            <label v-if="!editingPolicy"><Checkbox v-model="schedule.runImmediately" binary /> Also run once when the agent receives this policy</label>
            <p class="muted">Saving refreshes the cached source and storage credentials. Container replacement requires reviewing the newly discovered source. Recovery keys stay in the panel.</p>
          </template>
        </template>
        <template v-else>
          <div class="field"><label>New instance name</label><InputText v-model="restore.instanceName" required pattern="[a-z][a-z0-9-]{1,30}" /></div><div class="field"><label>New database name</label><InputText v-model="restore.database" required /></div><div class="field"><label>New database owner</label><InputText v-model="restore.owner" required /></div><p class="muted">Uses a fresh 512 MB instance. Source ownership maps to the new owner; source roles, passwords and grants are not recreated. Unsupported extensions fail the restore.</p>
        </template>
        <Message v-if="formError" severity="error">{{ formError }}</Message><Button type="submit" :label="mode === 'storage' ? 'Create destination and recovery key' : mode === 'backup' ? 'Allow export and create encrypted backup' : mode === 'policy' ? 'Allow recurring exports and save schedule' : 'Restore and verify'" :loading="busy" :disabled="(mode === 'backup' || mode === 'policy') && (!database || !destinationId || !container)" />
      </form>
    </Dialog>
    <Dialog :visible="recoveryIdentity !== null" modal header="Save your backup recovery key" :closable="false" :style="{ width: 'min(640px, 95vw)' }"><p>This key decrypts backups independently of pgcp. Save it in a password manager or offline location.</p><pre class="uri" style="white-space: pre-wrap; overflow-wrap: anywhere">{{ recoveryIdentity }}</pre><CopyButton :text="recoveryIdentity ?? ''" label="Copy recovery key" /><p class="muted">An encrypted copy remains in pgcp for restore verification. This export is shown once.</p><Button label="I saved the key" @click="recoveryIdentity = null" /></Dialog>
    <JobDialog :job-id="jobId" @close="jobId = null; load()" @done="load" />
  </div>
</template>
