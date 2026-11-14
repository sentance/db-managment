<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import { api, errorMessage } from '@/api/client'
import type { BackupSource, ContainerInfo, DiscoveredContainer, ServerStatus } from '@/api/types'
import { ago, bytes, dateTime } from '@/format'

const sources = ref<BackupSource[]>([])
const error = ref('')
const loading = ref(false)
const busy = ref<string | null>(null)
const editing = ref<BackupSource | null>(null)
const container = ref<ContainerInfo | null>(null)
const stanza = ref('')
const hours = ref(26)
const formError = ref('')
const saving = ref(false)
const selectedId = ref<string | null>(null)
const selected = computed(() => sources.value.find(s => s.instanceId === selectedId.value))
const history = computed(() => [...(selected.value?.snapshot?.catalogue.backups ?? [])]
  .sort((a, b) => Date.parse(b.finishedAt) - Date.parse(a.finishedAt)))
const unknownCount = computed(() => sources.value.filter(s => !s.bindingId).length)
let timer: ReturnType<typeof setInterval>

function label(value: string): string { return value.toLowerCase().replace(/_/g, ' ') }
function severity(value: string): 'success' | 'warn' | 'danger' | 'secondary' | 'info' {
  if (['OK', 'FRESH', 'AVAILABLE', 'ENCRYPTED', 'RECENT_SUCCESS'].includes(value)) return 'success'
  if (['UNREACHABLE', 'ERROR', 'REPOSITORY_ERROR', 'BACKUP_ERRORS', 'BINDING_STALE'].includes(value)) return 'danger'
  if (['STALE', 'OUTDATED', 'UNENCRYPTED', 'OFF', 'NO_RECENT_SUCCESS', 'NO_BACKUPS'].includes(value)) return 'warn'
  return 'secondary'
}
function lastBackup(source: BackupSource): string | null {
  return source.snapshot?.catalogue.backups.filter(b => !b.error)
    .map(b => b.finishedAt).sort().reverse()[0] ?? null
}
async function load() {
  loading.value = true
  error.value = ''
  try { sources.value = await api.get<BackupSource[]>('/api/backups') }
  catch (e) { error.value = errorMessage(e) }
  finally { loading.value = false }
}
async function configure(source: BackupSource) {
  editing.value = source
  container.value = null
  stanza.value = source.stanza ?? ''
  hours.value = source.staleAfterHours ?? 26
  formError.value = ''
  try {
    const servers = await api.get<ServerStatus[]>('/api/servers')
    if (editing.value?.instanceId !== source.instanceId) return
    for (const server of servers.filter(s => s.agentReachable)) {
      const discovered = await api.get<DiscoveredContainer[]>(`/api/servers/${server.server.id}/containers`)
      if (editing.value?.instanceId !== source.instanceId) return
      container.value = discovered.find(c => c.container.name === source.containerName)?.container ?? null
      if (container.value) break
    }
    if (!container.value) formError.value = 'The registered PostgreSQL container is not available. Check its registration and the agent.'
  } catch (e) { formError.value = errorMessage(e) }
}
async function save() {
  if (!editing.value || !container.value) return
  saving.value = true
  formError.value = ''
  try {
    const id = editing.value.instanceId
    await api.put(`/api/backups/sources/${id}`, {
      containerName: container.value.name, containerId: container.value.id,
      stanza: stanza.value, staleAfterHours: hours.value,
    })
    editing.value = null
    await refresh(id)
  } catch (e) { formError.value = errorMessage(e) }
  finally { saving.value = false }
}
async function refresh(id: string) {
  busy.value = id
  error.value = ''
  try {
    const updated = await api.post<BackupSource>(`/api/backups/sources/${id}/refresh`)
    sources.value = sources.value.map(s => s.instanceId === id ? updated : s)
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = null }
}
async function disconnect(id: string) {
  busy.value = id
  error.value = ''
  try { await api.post(`/api/backups/sources/${id}/disconnect`); await load() }
  catch (e) { error.value = errorMessage(e) }
  finally { busy.value = null }
}
onMounted(() => { load(); timer = setInterval(load, 30_000) })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>Backups</h1><p class="subtitle">Backup coverage and recovery evidence for your PostgreSQL instances.</p></div>
      <Button label="Reload status" icon="pi pi-refresh" text :loading="loading" @click="load" />
    </div>
    <Message v-if="error" severity="error">{{ error }}</Message>
    <Message severity="info" :closable="false">
      Connect an existing pgBackRest repository to inspect its backup history. Catalogues refresh every five minutes.
      Backup creation and restore workflows are the next milestone. A listed backup has not yet been restore-tested by pgcp.
    </Message>
    <Message v-if="unknownCount" severity="warn" :closable="false">
      {{ unknownCount }} instance(s) have unknown backup coverage in pgcp. Existing tools may provide backups; connect a source to inspect them.
    </Message>
    <div class="panel">
      <p v-if="!loading && !sources.length">Register your existing PostgreSQL instances on the Servers or Instances page to track their backup coverage.</p>
      <DataTable v-else :value="sources" :loading="loading" size="small" data-key="instanceId">
        <Column header="Instance"><template #body="{ data }">
          <RouterLink :to="`/instances/${data.instanceId}`">{{ data.instanceName }}</RouterLink>
          <div class="muted">{{ data.provider ?? 'No source connected' }}<span v-if="data.stanza"> · {{ data.stanza }}</span></div>
        </template></Column>
        <Column header="Inspection"><template #body="{ data }">
          <Tag :value="label(data.inspectionState)" :severity="severity(data.inspectionState)" />
          <div class="muted">{{ ago(data.checkedAt) }}</div>
        </template></Column>
        <Column header="Backups"><template #body="{ data }">
          <Tag :value="label(data.backupState)" :severity="severity(data.backupState)" />
          <div>{{ dateTime(lastBackup(data)) }}</div>
          <Tag v-if="data.freshness !== 'UNKNOWN'" :value="label(data.freshness)" :severity="severity(data.freshness)" />
        </template></Column>
        <Column header="Protection"><template #body="{ data }">
          <div><Tag :value="label(data.encryption)" :severity="severity(data.encryption)" /></div>
          <div class="muted">WAL: {{ label(data.archiveHealth) }}</div>
          <div class="muted">{{ label(data.verificationState) }}</div>
        </template></Column>
        <Column header="Actions"><template #body="{ data }">
          <div class="actions">
            <Button v-if="data.containerName" :label="data.bindingId ? 'Review source' : 'Connect source'" size="small" text @click="configure(data)" />
            <span v-else class="muted">Register a Docker container to inspect pgBackRest.</span>
            <Button v-if="data.bindingId" icon="pi pi-refresh" aria-label="Inspect source" text :loading="busy === data.instanceId" :disabled="busy !== null" @click="refresh(data.instanceId)" />
            <Button v-if="data.snapshot || data.error" label="Details" size="small" text @click="selectedId = data.instanceId" />
          </div>
        </template></Column>
      </DataTable>
    </div>

    <Dialog :visible="editing !== null" modal header="Connect existing backup source" :style="{ width: 'min(560px, 95vw)' }" @update:visible="v => !v && (editing = null)">
      <form class="form" @submit.prevent="save">
        <p>Allow pgcp to read the existing backup catalogue for <strong>{{ editing?.instanceName }}</strong>.</p>
        <p class="muted">This connection does not enable backups, change the source database, or take over its existing schedules and retention.</p>
        <dl v-if="container" class="kv"><dt>Source container</dt><dd><code>{{ container.name }}</code></dd><dt>Current identity</dt><dd class="mono">{{ container.id.slice(0, 12) }}</dd></dl>
        <div class="field"><label for="backup-stanza">Existing pgBackRest stanza</label><InputText id="backup-stanza" v-model="stanza" required pattern="[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}" placeholder="e.g. shared" /></div>
        <div class="field"><label for="backup-hours">Warn when latest backup is older than (hours)</label><InputNumber input-id="backup-hours" v-model="hours" :min="1" :max="8760" :use-grouping="false" /></div>
        <Message v-if="formError" severity="error">{{ formError }}</Message>
        <div class="actions">
          <Button v-if="editing?.bindingId" label="Disconnect source" severity="secondary" text :disabled="saving" @click="disconnect(editing!.instanceId).then(() => editing = null)" />
          <Button type="submit" label="Allow catalogue inspection" :loading="saving" :disabled="!container || !stanza || !hours" />
        </div>
      </form>
    </Dialog>

    <Dialog :visible="selected !== undefined" modal :header="`${selected?.instanceName} — backup evidence`" :style="{ width: 'min(960px, 95vw)' }" @update:visible="v => !v && (selectedId = null)">
      <template v-if="selected">
        <Message v-if="selected.error" severity="error" :closable="false">{{ selected.error }} Last successful inspection: {{ dateTime(selected.lastSuccessAt) }}. Any history below is cached.</Message>
        <p>Last inspected {{ dateTime(selected.checkedAt) }} · {{ label(selected.verificationState) }}</p>
        <p class="muted">WAL observations and listed archive ranges do not guarantee continuous coverage or a tested recovery window.</p>
        <DataTable :value="history" size="small" paginator :rows="10" data-key="label">
          <Column field="label" header="Backup" /><Column field="type" header="Type" />
          <Column header="Completed"><template #body="{ data }">{{ dateTime(data.finishedAt) }}</template></Column>
          <Column header="Database size"><template #body="{ data }">{{ bytes(data.databaseBytes) }}</template></Column>
          <Column header="Stored size"><template #body="{ data }">{{ bytes(data.repositoryBytes) }}</template></Column>
          <Column header="Result"><template #body="{ data }"><Tag :value="data.error ? 'has errors' : 'completed'" :severity="data.error ? 'danger' : 'info'" /></template></Column>
          <template #empty>No backups were listed in this catalogue.</template>
        </DataTable>
        <div v-for="repo in selected.snapshot?.catalogue.repositories ?? []" :key="repo.key" class="muted">Repository {{ repo.key }} · {{ repo.cipher }} · status code {{ repo.statusCode }}</div>
      </template>
    </Dialog>
  </div>
</template>
