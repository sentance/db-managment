<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Password from 'primevue/password'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import Textarea from 'primevue/textarea'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import StatusTag from '@/components/StatusTag.vue'
import JobDialog from '@/components/JobDialog.vue'
import CredentialsDialog from '@/components/CredentialsDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { CreateInstanceResult, Exposure, ExternalMode, Info, Instance, IssuedCredential, Protection } from '@/api/types'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const instances = ref<Instance[]>([])
const info = ref<Info | null>(null)
const loading = ref(false)

const protections: Protection[] = ['PRODUCTION', 'STANDARD', 'EPHEMERAL']
const exposures: { label: string; value: Exposure }[] = [
  { label: 'Docker networks only (recommended)', value: 'NONE' },
  { label: 'Host loopback 127.0.0.1 (SSH tunnel)', value: 'LOCALHOST' },
  { label: 'Public port (TLS enforced)', value: 'PUBLIC' },
]

const showCreate = ref(false)
const create = ref({
  name: '', pgMajor: 18, memoryMb: 1024, cpus: 1, protection: 'STANDARD' as Protection, exposure: 'NONE' as Exposure,
  hostPort: 5433, networks: '', settings: '', dbName: '', dbOwner: '', readOnly: true,
})
const creating = ref(false)
const createError = ref('')

const showRegister = ref(false)
const reg = ref({
  name: '', host: '', port: 5432, maintenanceDb: 'postgres', username: 'postgres', password: '', sslMode: 'prefer',
  mode: 'MONITOR_ONLY' as ExternalMode, protection: 'PRODUCTION' as Protection, containerName: '',
})
const registering = ref(false)
const registerError = ref('')

const jobId = ref<string | null>(null)
const credentials = ref<IssuedCredential[] | null>(null)
const majors = computed(() => info.value?.supportedPgMajors ?? [14, 15, 16, 17, 18])

async function load() {
  loading.value = true
  try {
    instances.value = await api.get<Instance[]>('/api/instances')
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot load instances', detail: errorMessage(e), life: 6000 })
  } finally {
    loading.value = false
  }
}

function parseSettings(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of text.split('\n')) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const i = t.indexOf('=')
    if (i > 0) out[t.slice(0, i).trim()] = t.slice(i + 1).trim()
  }
  return out
}

async function submitCreate() {
  creating.value = true
  createError.value = ''
  const c = create.value
  try {
    const r = await api.post<CreateInstanceResult>('/api/instances/managed', {
      name: c.name, pgMajor: c.pgMajor, memoryMb: c.memoryMb, cpus: c.cpus, protection: c.protection, exposure: c.exposure,
      hostPort: c.exposure === 'NONE' ? null : c.hostPort,
      extraNetworks: c.networks.split(',').map((s) => s.trim()).filter(Boolean),
      settings: parseSettings(c.settings),
      initialDatabase: c.dbName ? { name: c.dbName, owner: c.dbOwner || null, createReadOnlyRole: c.readOnly } : null,
    })
    showCreate.value = false
    jobId.value = r.jobId
    if (r.credentials.length) credentials.value = r.credentials
    load()
  } catch (e) {
    createError.value = errorMessage(e)
  } finally {
    creating.value = false
  }
}

async function submitRegister() {
  registering.value = true
  registerError.value = ''
  try {
    const r = reg.value
    const created = await api.post<Instance>('/api/instances/external', { ...r, containerName: r.containerName || null })
    showRegister.value = false
    toast.add({ severity: 'success', summary: `Registered ${created.name}`, detail: 'No changes were made to the server.', life: 4000 })
    router.push({ name: 'instance', params: { id: created.id } })
  } catch (e) {
    registerError.value = errorMessage(e)
  } finally {
    registering.value = false
  }
}

onMounted(async () => {
  load()
  info.value = await api.get<Info>('/api/settings/info').catch(() => null)
  if (typeof route.query.register === 'string') {
    const container = route.query.register
    reg.value.containerName = container
    reg.value.host = container
    reg.value.name = container.replace(/[^a-z0-9-]/g, '-').replace(/^[^a-z]+/, '').slice(0, 31) || 'existing'
    showRegister.value = true
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>Instances</h1><p class="subtitle">PostgreSQL clusters. Physical backups and PITR apply to a whole instance.</p></div>
      <div class="actions">
        <Button label="Register existing" icon="pi pi-link" outlined @click="showRegister = true" />
        <Button label="New instance" icon="pi pi-plus" @click="showCreate = true" />
      </div>
    </div>
    <div class="panel">
      <DataTable :value="instances" :loading="loading" data-key="id" row-hover @row-click="(e) => router.push({ name: 'instance', params: { id: e.data.id } })" style="cursor: pointer">
        <Column header="Name"><template #body="{ data }"><strong>{{ data.name }}</strong></template></Column>
        <Column header="Kind">
          <template #body="{ data }">
            <Tag :value="data.kind.toLowerCase()" :severity="data.kind === 'MANAGED' ? 'success' : 'secondary'" />
            <Tag v-if="data.externalMode === 'MONITOR_ONLY'" value="monitor only" severity="secondary" style="margin-left: 4px" />
          </template>
        </Column>
        <Column header="Status"><template #body="{ data }"><StatusTag :status="data.status" /></template></Column>
        <Column header="Version"><template #body="{ data }">{{ data.pgMajor ?? '—' }}</template></Column>
        <Column header="Endpoint"><template #body="{ data }"><span class="mono">{{ data.host }}:{{ data.port }}</span><span v-if="data.hostPort" class="muted"> · {{ data.bindAddress }}:{{ data.hostPort }}</span></template></Column>
        <Column header="Protection"><template #body="{ data }"><Tag :value="data.protection" :severity="data.protection === 'PRODUCTION' ? 'danger' : 'secondary'" /></template></Column>
        <template #empty><div class="empty">No instances yet. Create a managed instance or register an existing PostgreSQL.</div></template>
      </DataTable>
    </div>

    <Dialog v-model:visible="showCreate" modal header="New managed instance" :style="{ width: '720px' }">
      <form class="form" @submit.prevent="submitCreate">
        <div class="field-row">
          <div class="field"><label>Name</label><InputText v-model="create.name" placeholder="app-prod" autofocus /><small>a-z, 0-9, dashes</small></div>
          <div class="field"><label>PostgreSQL</label><Select v-model="create.pgMajor" :options="majors" /></div>
          <div class="field"><label>Protection</label><Select v-model="create.protection" :options="protections" /></div>
        </div>
        <div class="field-row">
          <div class="field"><label>Memory limit (MB)</label><InputNumber v-model="create.memoryMb" :min="256" :step="256" show-buttons /></div>
          <div class="field"><label>CPUs</label><InputNumber v-model="create.cpus" :min="0.1" :step="0.5" :max-fraction-digits="1" show-buttons /></div>
        </div>
        <div class="field-row">
          <div class="field"><label>Network exposure</label><Select v-model="create.exposure" :options="exposures" option-label="label" option-value="value" /></div>
          <div v-if="create.exposure !== 'NONE'" class="field"><label>Host port</label><InputNumber v-model="create.hostPort" :min="1024" :max="65535" :use-grouping="false" /></div>
        </div>
        <Message v-if="create.exposure === 'PUBLIC'" severity="warn" :closable="false">The port is reachable from the internet. pg_hba requires TLS and SCRAM; make sure your firewall rules are intended.</Message>
        <div class="field"><label>Extra Docker networks</label><InputText v-model="create.networks" placeholder="coolify" /><small>Comma separated; lets containers on these networks reach the instance by name.</small></div>
        <h3 style="margin: 4px 0 0">Initial database (optional)</h3>
        <div class="field-row">
          <div class="field"><label>Database</label><InputText v-model="create.dbName" placeholder="app" /></div>
          <div class="field"><label>Owner role</label><InputText v-model="create.dbOwner" :placeholder="create.dbName ? create.dbName + '_owner' : 'app_owner'" /></div>
        </div>
        <div class="check"><Checkbox v-model="create.readOnly" binary input-id="ro" /><label for="ro">Also create a read-only role</label></div>
        <div class="field"><label>PostgreSQL parameters (optional)</label>
          <Textarea v-model="create.settings" rows="3" placeholder="work_mem=16MB&#10;statement_timeout=30s" class="mono" />
          <small>Memory-based defaults are applied automatically. Allowed: {{ Object.keys(info?.settingsAllowlist ?? {}).join(', ') }}</small>
        </div>
        <Message v-if="createError" severity="error">{{ createError }}</Message>
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Create instance" icon="pi pi-check" :loading="creating" :disabled="!create.name" /></div>
      </form>
    </Dialog>

    <Dialog v-model:visible="showRegister" modal header="Register existing PostgreSQL" :style="{ width: '640px' }">
      <form class="form" @submit.prevent="submitRegister">
        <Message severity="info" :closable="false">Registration only connects and runs read-only queries. pgcp never restarts, reconfigures or upgrades external instances.</Message>
        <div class="field-row">
          <div class="field"><label>Name in pgcp</label><InputText v-model="reg.name" /></div>
          <div class="field"><label>Linked container (optional)</label><InputText v-model="reg.containerName" placeholder="for logs and libc detection" /></div>
        </div>
        <div class="field-row">
          <div class="field"><label>Host</label><InputText v-model="reg.host" /><small>Container name if pgcp shares its Docker network</small></div>
          <div class="field"><label>Port</label><InputNumber v-model="reg.port" :use-grouping="false" /></div>
          <div class="field"><label>Database</label><InputText v-model="reg.maintenanceDb" /></div>
        </div>
        <div class="field-row">
          <div class="field"><label>User</label><InputText v-model="reg.username" autocomplete="off" /><small>Prefer a dedicated role with pg_monitor</small></div>
          <div class="field"><label>Password</label><Password v-model="reg.password" :feedback="false" toggle-mask fluid autocomplete="new-password" /></div>
          <div class="field"><label>SSL mode</label><Select v-model="reg.sslMode" :options="['disable', 'prefer', 'require', 'verify-ca', 'verify-full']" /></div>
        </div>
        <div class="field-row">
          <div class="field"><label>Mode</label><Select v-model="reg.mode" :options="['MONITOR_ONLY', 'MANAGE_OBJECTS']" /></div>
          <div class="field"><label>Protection</label><Select v-model="reg.protection" :options="protections" /></div>
        </div>
        <Message v-if="reg.mode === 'MANAGE_OBJECTS'" severity="warn" :closable="false">pgcp will be allowed to create databases and roles on this server. Requires authenticator confirmation.</Message>
        <Message v-if="registerError" severity="error">{{ registerError }}</Message>
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Test & register" icon="pi pi-link" :loading="registering" :disabled="!reg.name || !reg.host" /></div>
      </form>
    </Dialog>

    <JobDialog :job-id="jobId" @close="jobId = null" @done="load" />
    <CredentialsDialog :visible="credentials !== null && jobId === null" title="Initial database credentials" :credentials="credentials ?? []" :connections="[]" @close="credentials = null" />
  </div>
</template>
