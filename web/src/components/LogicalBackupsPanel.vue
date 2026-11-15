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
import CopyButton from './CopyButton.vue'
import JobDialog from './JobDialog.vue'
import StatusTag from './StatusTag.vue'
import { api, errorMessage } from '@/api/client'
import type { BackupDestination, LogicalBackup, LogicalRestore, Instance, DatabaseView, ServerStatus, DiscoveredContainer } from '@/api/types'
import { bytes, dateTime } from '@/format'

defineProps<{ restoreOnly?: boolean }>()
const destinations = ref<BackupDestination[]>([]), backups = ref<LogicalBackup[]>([]), restores = ref<LogicalRestore[]>([]), instances = ref<Instance[]>([])
const error = ref(''), formError = ref(''), busy = ref(false), jobId = ref<string | null>(null)
const mode = ref<'storage' | 'backup' | 'restore' | null>(null), recoveryIdentity = ref<string | null>(null)
const destination = reactive({ name: '', endpoint: '', region: 'auto', bucket: '', accessKey: '', secretKey: '' })
const sourceId = ref<string | null>(null), database = ref<string | null>(null), destinationId = ref<string | null>(null)
const databases = ref<DatabaseView[]>([]), container = ref<DiscoveredContainer['container'] | null>(null)
const backupId = ref(''), restore = reactive({ instanceName: '', database: 'restored', owner: 'restored_owner' })
const verified = computed(() => destinations.value.filter(d => d.status === 'VERIFIED'))
let timer: ReturnType<typeof setInterval>
async function load() {
  try {
    const values = await Promise.all([api.get<BackupDestination[]>('/api/backups/destinations'), api.get<LogicalBackup[]>('/api/backups/logical'), api.get<LogicalRestore[]>('/api/backups/restores'), api.get<Instance[]>('/api/instances')])
    ;[destinations.value, backups.value, restores.value, instances.value] = values
    error.value = ''
  } catch (e) { error.value = errorMessage(e) }
}
function open(next: typeof mode.value) { mode.value = next; formError.value = '' }
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
    <div v-if="!restoreOnly" class="panel">
      <div class="page-header"><h2>Encrypted database backups</h2><div class="actions"><Button label="Add storage" outlined size="small" @click="open('storage')" /><Button label="Back up a database" size="small" :disabled="!verified.length" @click="open('backup')" /></div></div>
      <p class="muted">Exports are encrypted before upload to your S3-compatible storage. Automatic schedules are a later milestone.</p>
      <DataTable :value="destinations" size="small"><Column field="name" header="Destination" /><Column field="bucket" header="Bucket" /><Column field="prefix" header="Owned prefix" /><Column header="Connection"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column><Column header=""><template #body="{ data }"><Button label="Test read / write" text size="small" @click="test(data.id)" /></template></Column><template #empty>Add storage, save its recovery key, and test the connection.</template></DataTable>
    </div>
    <div class="panel"><h2>Saved logical backups</h2>
      <DataTable :value="backups" size="small" paginator :rows="10" data-key="id">
        <Column header="Source"><template #body="{ data }">{{ instances.find(i => i.id === data.instanceId)?.name ?? data.instanceId }}</template></Column>
        <Column field="databaseName" header="Database" /><Column header="Created"><template #body="{ data }">{{ dateTime(data.createdAt) }}</template></Column><Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column>
        <Column header="Encrypted size"><template #body="{ data }">{{ bytes(data.result?.sizeBytes ? Number(data.result.sizeBytes) : null) }}</template></Column><Column header="Last verified restore"><template #body="{ data }">{{ data.verifiedAt ? dateTime(data.verifiedAt) : 'Never verified' }}</template></Column>
        <Column header=""><template #body="{ data }"><div class="actions"><Button label="Job" text size="small" @click="jobId = data.jobId" /><Button label="Restore to new instance" text size="small" :disabled="data.status !== 'SUCCEEDED'" @click="backupId = data.id; restore.instanceName = ''; open('restore')" /></div></template></Column><template #empty>No logical backups have been created yet.</template>
      </DataTable>
    </div>
    <div class="panel"><h2>Restore history</h2><p class="muted">Destinations stay on a separate internal network with no published ports. Applications keep using their original databases.</p>
      <DataTable :value="restores" size="small" paginator :rows="10" data-key="id"><Column field="targetName" header="New instance" /><Column field="databaseName" header="Database" /><Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column><Column header="Checks"><template #body="{ data }">{{ data.result?.verified === 'true' ? `${data.result.tablesChecked} tables · ${data.result.rowsChecked} rows` : 'Not verified' }}</template></Column><Column header=""><template #body="{ data }"><Button label="Job" text size="small" @click="jobId = data.jobId" /><RouterLink v-if="data.targetInstanceId" :to="`/instances/${data.targetInstanceId}`">Open instance</RouterLink></template></Column></DataTable>
    </div>
    <Dialog :visible="mode !== null" modal :header="mode === 'storage' ? 'Add S3-compatible storage' : mode === 'backup' ? 'Back up a database' : 'Restore into a new isolated instance'" :style="{ width: 'min(600px, 95vw)' }" @update:visible="v => !v && (mode = null)">
      <form class="form" @submit.prevent="submit">
        <template v-if="mode === 'storage'">
          <div class="field"><label>Name</label><InputText v-model="destination.name" required maxlength="100" /></div><div class="field"><label>HTTPS endpoint (empty for AWS S3)</label><InputText v-model="destination.endpoint" /></div>
          <div class="field-row"><div class="field"><label>Region</label><InputText v-model="destination.region" required /></div><div class="field"><label>Existing bucket</label><InputText v-model="destination.bucket" required /></div></div>
          <div class="field"><label>Access key</label><InputText v-model="destination.accessKey" required autocomplete="off" /></div><div class="field"><label>Secret key</label><Password v-model="destination.secretKey" required :feedback="false" toggle-mask autocomplete="new-password" /></div><p class="muted">A new owned prefix and encryption key will be created. Save the recovery key outside this server when it is displayed.</p>
        </template>
        <template v-else-if="mode === 'backup'">
          <div class="field"><label>Instance</label><Select v-model="sourceId" :options="instances.filter(i => i.containerName)" option-label="name" option-value="id" @change="selectSource" /></div><div class="field"><label>Database</label><Select v-model="database" :options="databases.filter(d => !d.system)" option-label="name" option-value="name" /></div><div class="field"><label>Storage</label><Select v-model="destinationId" :options="verified" option-label="name" option-value="id" /></div>
          <p v-if="container" class="muted">Source: {{ container.name }} · {{ container.id.slice(0, 12) }}</p><p class="muted">Export uses the registered login and reads all tables for recovery checks. It does not grant privileges or change source configuration.</p>
        </template>
        <template v-else>
          <div class="field"><label>New instance name</label><InputText v-model="restore.instanceName" required pattern="[a-z][a-z0-9-]{1,30}" /></div><div class="field"><label>New database name</label><InputText v-model="restore.database" required /></div><div class="field"><label>New database owner</label><InputText v-model="restore.owner" required /></div><p class="muted">Uses a fresh 512 MB instance. Source ownership maps to the new owner; source roles, passwords and grants are not recreated. Unsupported extensions fail the restore.</p>
        </template>
        <Message v-if="formError" severity="error">{{ formError }}</Message><Button type="submit" :label="mode === 'storage' ? 'Create destination and recovery key' : mode === 'backup' ? 'Allow export and create encrypted backup' : 'Restore and verify'" :loading="busy" :disabled="mode === 'backup' && (!database || !destinationId || !container)" />
      </form>
    </Dialog>
    <Dialog :visible="recoveryIdentity !== null" modal header="Save your backup recovery key" :closable="false" :style="{ width: 'min(640px, 95vw)' }"><p>This key decrypts backups independently of pgcp. Save it in a password manager or offline location.</p><pre class="uri" style="white-space: pre-wrap; overflow-wrap: anywhere">{{ recoveryIdentity }}</pre><CopyButton :text="recoveryIdentity ?? ''" label="Copy recovery key" /><p class="muted">An encrypted copy remains in pgcp for restore verification. This export is shown once.</p><Button label="I saved the key" @click="recoveryIdentity = null" /></Dialog>
    <JobDialog :job-id="jobId" @close="jobId = null; load()" @done="load" />
  </div>
</template>
