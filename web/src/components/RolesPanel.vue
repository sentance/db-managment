<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import ConfirmNameDialog from './ConfirmNameDialog.vue'
import CredentialsDialog from './CredentialsDialog.vue'
import JobDialog from './JobDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { Access, DatabaseView, Dependency, Instance, PasswordResult, RoleView, Submitted } from '@/api/types'

const props = defineProps<{ instance: Instance }>()
const toast = useToast()
const roles = ref<RoleView[]>([])
const databases = ref<DatabaseView[]>([])
const loading = ref(false)
const base = computed(() => `/api/instances/${props.instance.id}/roles`)
const writable = computed(() => props.instance.objectsWritable && props.instance.status === 'RUNNING')
const dbOptions = computed(() => databases.value.filter((d) => !d.system).map((d) => d.name))
const accessOptions = [
  { label: 'Read only', value: 'READ_ONLY' },
  { label: 'Read & write', value: 'READ_WRITE' },
  { label: 'Revoke access', value: null },
]

const creating = ref(false)
const newRole = ref({ name: '', database: undefined as string | undefined, access: 'READ_ONLY' as Access })
const issued = ref<PasswordResult | null>(null)
const accessFor = ref<RoleView | null>(null)
const accessForm = ref<{ database?: string; access: Access | null }>({ access: 'READ_ONLY' })
const depsFor = ref<{ role: string; deps: Dependency[] } | null>(null)
const dropping = ref<RoleView | null>(null)
const jobId = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    ;[roles.value, databases.value] = await Promise.all([
      api.get<RoleView[]>(base.value),
      api.get<DatabaseView[]>(`/api/instances/${props.instance.id}/databases`),
    ])
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot load roles', detail: errorMessage(e), life: 6000 })
  } finally {
    loading.value = false
  }
}

async function run<T>(fn: () => Promise<T>, success?: string): Promise<T | undefined> {
  try {
    const r = await fn()
    if (success) toast.add({ severity: 'success', summary: success, life: 2500 })
    return r
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Operation failed', detail: errorMessage(e), life: 7000 })
    return undefined
  }
}

async function create() {
  const grants = newRole.value.database ? [{ database: newRole.value.database, access: newRole.value.access }] : []
  const r = await run(() => api.post<PasswordResult>(base.value, { name: newRole.value.name, grants }))
  if (r) {
    creating.value = false
    issued.value = r
    newRole.value = { name: '', database: undefined, access: 'READ_ONLY' }
    load()
  }
}

async function rotate(role: RoleView) {
  const r = await run(() => api.post<PasswordResult>(`${base.value}/${encodeURIComponent(role.name)}/rotate-password`))
  if (r) {
    issued.value = r
    load()
  }
}

async function reveal(role: RoleView) {
  const r = await run(() => api.post<PasswordResult>(`${base.value}/${encodeURIComponent(role.name)}/reveal-password`))
  if (r) issued.value = r
}

async function setLocked(role: RoleView, locked: boolean) {
  if (await run(() => api.put(`${base.value}/${encodeURIComponent(role.name)}/lock`, { locked }), locked ? 'Role locked' : 'Role unlocked')) load()
}

async function applyAccess() {
  const role = accessFor.value!
  const ok = await run(() => api.put(`${base.value}/${encodeURIComponent(role.name)}/access`, accessForm.value), 'Access updated')
  if (ok) {
    accessFor.value = null
    load()
  }
}

async function showDeps(role: RoleView) {
  const deps = await run(() => api.get<Dependency[]>(`${base.value}/${encodeURIComponent(role.name)}/dependencies`))
  if (deps) depsFor.value = { role: role.name, deps }
}

async function drop(name: string) {
  const r = await run(() => api.post<Submitted>(`${base.value}/${encodeURIComponent(name)}/drop`, { confirmName: name }))
  dropping.value = null
  if (r?.jobId) jobId.value = r.jobId
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div style="display: flex; justify-content: space-between; align-items: center">
      <h2 style="margin: 0">Users & permissions</h2>
      <div class="actions">
        <Button icon="pi pi-refresh" text @click="load" :loading="loading" />
        <Button v-if="writable" label="New role" icon="pi pi-plus" size="small" @click="creating = true" />
      </div>
    </div>
    <Message v-if="!instance.objectsWritable" severity="info" :closable="false" style="margin-top: 10px">
      This instance is registered in monitor-only mode: roles are shown but cannot be changed from pgcp.
    </Message>
    <DataTable :value="roles" :loading="loading" size="small" style="margin-top: 12px" data-key="name">
      <Column field="name" header="Role">
        <template #body="{ data }">
          <code>{{ data.name }}</code>
          <div class="chips" style="margin-top: 4px">
            <Tag v-if="data.superuser" value="superuser" severity="danger" />
            <Tag v-if="data.managed" :value="data.kind" severity="info" />
            <Tag v-if="data.locked || !data.canLogin" :value="data.canLogin ? 'locked' : 'no login'" severity="warn" />
            <Tag v-if="data.protectedRole" value="protected" severity="secondary" />
          </div>
        </template>
      </Column>
      <Column header="Databases">
        <template #body="{ data }"><span class="mono">{{ data.databases.join(', ') || '—' }}</span></template>
      </Column>
      <Column header="Attributes">
        <template #body="{ data }">
          <span class="muted">{{ [data.createDb && 'CREATEDB', data.createRole && 'CREATEROLE', ...data.memberOf.map((m: string) => 'member of ' + m)].filter(Boolean).join(', ') || '—' }}</span>
        </template>
      </Column>
      <Column header="" style="width: 1%; white-space: nowrap">
        <template #body="{ data }">
          <div class="actions" style="flex-wrap: nowrap">
            <Button v-if="data.passwordStored" icon="pi pi-eye" text size="small" v-tooltip.top="'Show password'" @click="reveal(data)" />
            <template v-if="writable && !data.protectedRole">
              <Button icon="pi pi-key" text size="small" v-tooltip.top="'Rotate password'" @click="rotate(data)" />
              <Button icon="pi pi-sliders-h" text size="small" v-tooltip.top="'Database access'" @click="accessFor = data; accessForm = { database: undefined, access: 'READ_ONLY' }" />
              <Button :icon="data.canLogin ? 'pi pi-lock' : 'pi pi-lock-open'" text size="small" severity="warn"
                v-tooltip.top="data.canLogin ? 'Lock (NOLOGIN, ends sessions)' : 'Unlock'" @click="setLocked(data, data.canLogin)" />
              <Button icon="pi pi-sitemap" text size="small" v-tooltip.top="'Dependencies'" @click="showDeps(data)" />
              <Button icon="pi pi-trash" text size="small" severity="danger" v-tooltip.top="'Drop role'" @click="dropping = data" />
            </template>
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="creating" modal header="New role" :style="{ width: '480px' }">
      <form class="form" @submit.prevent="create">
        <div class="field">
          <label>Name</label>
          <InputText v-model="newRole.name" placeholder="app_reader" autofocus />
          <small>Lowercase letters, digits and underscores. A strong password is generated.</small>
        </div>
        <div class="field-row">
          <div class="field"><label>Database (optional)</label><Select v-model="newRole.database" :options="dbOptions" show-clear /></div>
          <div class="field"><label>Access</label><Select v-model="newRole.access" :options="accessOptions.slice(0, 2)" option-label="label" option-value="value" /></div>
        </div>
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Create role" :disabled="!newRole.name" /></div>
      </form>
    </Dialog>

    <Dialog :visible="accessFor !== null" modal :header="`Database access · ${accessFor?.name}`" :style="{ width: '440px' }" @update:visible="(v) => !v && (accessFor = null)">
      <form class="form" @submit.prevent="applyAccess">
        <div class="field"><label>Database</label><Select v-model="accessForm.database" :options="dbOptions" /></div>
        <div class="field"><label>Access</label><Select v-model="accessForm.access" :options="accessOptions" option-label="label" option-value="value" /></div>
        <small class="muted">Grants cover all schemas owned by the database owner, including tables created later.</small>
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Apply" :disabled="!accessForm.database" /></div>
      </form>
    </Dialog>

    <Dialog :visible="depsFor !== null" modal :header="`Dependencies · ${depsFor?.role}`" :style="{ width: '560px' }" @update:visible="(v) => !v && (depsFor = null)">
      <p class="muted">A role can be dropped only when it owns nothing. Granted privileges are revoked automatically.</p>
      <DataTable :value="depsFor?.deps ?? []" size="small">
        <Column field="database" header="Database" />
        <Column field="kind" header="Kind" />
        <Column field="catalog" header="Object type" />
        <Column field="count" header="Count" />
      </DataTable>
    </Dialog>

    <ConfirmNameDialog :visible="dropping !== null" :title="`Drop role ${dropping?.name}`" :name="dropping?.name ?? ''"
      confirm-label="Drop role" @cancel="dropping = null" @confirm="drop">
      <p style="margin: 0">The role's sessions are terminated and its privileges revoked. Refused if it still owns objects.</p>
    </ConfirmNameDialog>
    <CredentialsDialog :visible="issued !== null" title="Role credentials" :credentials="issued ? [{ role: issued.role, kind: 'LOGIN', password: issued.password }] : []"
      :connections="issued?.connections ?? []" @close="issued = null" />
    <JobDialog :job-id="jobId" @close="jobId = null" @done="load" />
  </div>
</template>
