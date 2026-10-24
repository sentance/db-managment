<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Checkbox from 'primevue/checkbox'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import JobDialog from './JobDialog.vue'
import CredentialsDialog from './CredentialsDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { CreateDatabaseResult, DatabaseView, Instance } from '@/api/types'
import { bytes } from '@/format'

const props = defineProps<{ instance: Instance }>()
const router = useRouter()
const toast = useToast()
const rows = ref<DatabaseView[]>([])
const loading = ref(false)
const showCreate = ref(false)
const form = ref({ name: '', owner: '', createReadOnlyRole: true })
const error = ref('')
const busy = ref(false)
const created = ref<CreateDatabaseResult | null>(null)
const jobId = ref<string | null>(null)
const writable = computed(() => props.instance.objectsWritable && props.instance.status === 'RUNNING')

async function load() {
  loading.value = true
  try {
    rows.value = await api.get<DatabaseView[]>(`/api/instances/${props.instance.id}/databases`)
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot list databases', detail: errorMessage(e), life: 6000 })
  } finally {
    loading.value = false
  }
}

async function submit() {
  busy.value = true
  error.value = ''
  try {
    created.value = await api.post<CreateDatabaseResult>(`/api/instances/${props.instance.id}/databases`, {
      name: form.value.name, owner: form.value.owner || null, createReadOnlyRole: form.value.createReadOnlyRole,
    })
    showCreate.value = false
    jobId.value = created.value.jobId
    form.value = { name: '', owner: '', createReadOnlyRole: true }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div style="display: flex; justify-content: space-between; align-items: center">
      <h2 style="margin: 0">Databases</h2>
      <div class="actions">
        <Button icon="pi pi-refresh" text @click="load" :loading="loading" />
        <Button v-if="writable" label="New database" icon="pi pi-plus" size="small" @click="showCreate = true" />
      </div>
    </div>
    <DataTable :value="rows" :loading="loading" size="small" style="margin-top: 12px; cursor: pointer" row-hover
      @row-click="(e) => router.push({ name: 'database', params: { id: instance.id, name: e.data.name } })">
      <Column header="Name">
        <template #body="{ data }"><code>{{ data.name }}</code>
          <Tag v-if="data.managed" value="pgcp" severity="success" style="margin-left: 6px" />
          <Tag v-if="data.system" value="system" severity="secondary" style="margin-left: 6px" />
        </template>
      </Column>
      <Column field="owner" header="Owner" />
      <Column header="Size"><template #body="{ data }">{{ bytes(data.sizeBytes) }}</template></Column>
      <Column field="connections" header="Connections" />
      <Column header="Encoding"><template #body="{ data }"><span class="muted">{{ data.encoding }} · {{ data.collation }}</span></template></Column>
    </DataTable>

    <Dialog v-model:visible="showCreate" modal header="New database" :style="{ width: '480px' }">
      <form class="form" @submit.prevent="submit">
        <div class="field"><label>Name</label><InputText v-model="form.name" placeholder="app" autofocus /></div>
        <div class="field"><label>Owner role</label><InputText v-model="form.owner" :placeholder="(form.name || 'app') + '_owner'" />
          <small>A new login role with a generated password. PUBLIC loses CONNECT on the database.</small></div>
        <div class="check"><Checkbox v-model="form.createReadOnlyRole" binary input-id="dbro" /><label for="dbro">Create read-only role <code>{{ (form.name || 'app') + '_ro' }}</code></label></div>
        <Message v-if="error" severity="error">{{ error }}</Message>
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Create" :loading="busy" :disabled="!form.name" /></div>
      </form>
    </Dialog>
    <JobDialog :job-id="jobId" @close="jobId = null" @done="load" />
    <CredentialsDialog :visible="created !== null && jobId === null" title="Database credentials" :credentials="created?.credentials ?? []"
      :connections="created ? [created.connection] : []" @close="created = null" />
  </div>
</template>
