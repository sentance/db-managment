<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import ConnectionPanel from '@/components/ConnectionPanel.vue'
import ConfirmNameDialog from '@/components/ConfirmNameDialog.vue'
import JobDialog from '@/components/JobDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { DatabaseDetails, Instance, Info, Submitted } from '@/api/types'
import { bytes } from '@/format'

const props = defineProps<{ id: string; name: string }>()
const router = useRouter()
const toast = useToast()
const instance = ref<Instance | null>(null)
const details = ref<DatabaseDetails | null>(null)
const info = ref<Info | null>(null)
const error = ref('')
const dropping = ref(false)
const terminate = ref(false)
const renaming = ref(false)
const newName = ref('')
const jobId = ref<string | null>(null)
const writable = computed(() => !!instance.value?.objectsWritable && instance.value.status === 'RUNNING' && !details.value?.database.system)

async function load() {
  try {
    ;[instance.value, details.value] = await Promise.all([
      api.get<Instance>(`/api/instances/${props.id}`),
      api.get<DatabaseDetails>(`/api/instances/${props.id}/databases/${encodeURIComponent(props.name)}`),
    ])
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function drop(confirmName: string) {
  try {
    jobId.value = (await api.post<Submitted>(`/api/instances/${props.id}/databases/${encodeURIComponent(props.name)}/drop`, {
      confirmName, terminateConnections: terminate.value,
    })).jobId
    dropping.value = false
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Drop failed', detail: errorMessage(e), life: 7000 })
  }
}

async function rename() {
  try {
    jobId.value = (await api.post<Submitted>(`/api/instances/${props.id}/databases/${encodeURIComponent(props.name)}/rename`, { newName: newName.value })).jobId
    renaming.value = false
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Rename failed', detail: errorMessage(e), life: 7000 })
  }
}

function jobDone(j: { type: string; status: string }) {
  if (j.status !== 'SUCCEEDED') return
  if (j.type === 'database.drop') router.push({ name: 'instance', params: { id: props.id } })
  if (j.type === 'database.rename') router.replace({ name: 'database', params: { id: props.id, name: newName.value } })
}

onMounted(async () => {
  load()
  info.value = await api.get<Info>('/api/settings/info').catch(() => null)
})
</script>

<template>
  <div class="page">
    <Message v-if="error" severity="error">{{ error }}</Message>
    <template v-if="details && instance">
      <div class="page-header">
        <div>
          <h1><code>{{ name }}</code></h1>
          <p class="subtitle">on <RouterLink :to="{ name: 'instance', params: { id } }">{{ instance.name }}</RouterLink> ·
            owner <code>{{ details.database.owner }}</code> · {{ bytes(details.database.sizeBytes) }} · {{ details.database.connections }} connections
            <Tag v-if="details.database.managed" value="created by pgcp" severity="success" style="margin-left: 6px" /></p>
        </div>
        <div class="actions">
          <a v-if="info?.sqlEditorUrl" :href="info.sqlEditorUrl" target="_blank" rel="noopener noreferrer"><Button label="Open SQL editor" icon="pi pi-external-link" outlined size="small" /></a>
        </div>
      </div>

      <ConnectionPanel :instance-id="id" :database="name" :roles="details.loginRoles" :default-role="details.database.owner" :writable="instance.objectsWritable" />

      <div class="grid cols-2">
        <div class="panel">
          <h2>Extensions</h2>
          <Message v-if="details.extensionsError" severity="warn" :closable="false">{{ details.extensionsError }}</Message>
          <DataTable :value="details.extensions" size="small">
            <Column field="name" header="Extension" />
            <Column field="version" header="Version" />
            <Column field="schema" header="Schema" />
          </DataTable>
        </div>
        <div class="panel">
          <h2>Roles that can connect</h2>
          <div class="chips"><Tag v-for="r in details.loginRoles" :key="r" :value="r" severity="secondary" /></div>
          <p class="muted">Grant or revoke access under <RouterLink :to="{ name: 'roles', query: { instance: id } }">Users & Permissions</RouterLink>.</p>
        </div>
      </div>

      <div v-if="writable" class="panel danger-zone">
        <h2>Danger zone</h2>
        <div class="actions">
          <Button label="Rename…" outlined severity="warn" @click="renaming = true; newName = ''" />
          <Button label="Drop database…" outlined severity="danger" @click="dropping = true" />
        </div>
      </div>
    </template>

    <Dialog v-model:visible="renaming" modal header="Rename database" :style="{ width: '420px' }">
      <form class="form" @submit.prevent="rename">
        <p class="muted" style="margin: 0">Renaming requires that nobody is connected. Applications must be updated afterwards.</p>
        <InputText v-model="newName" placeholder="new_name" autofocus />
        <div class="actions" style="justify-content: flex-end"><Button type="submit" label="Rename" :disabled="!newName" /></div>
      </form>
    </Dialog>
    <ConfirmNameDialog :visible="dropping" :title="`Drop database ${name}`" :name="name" confirm-label="Drop database" @cancel="dropping = false" @confirm="drop">
      <Message severity="error" :closable="false">All data in this database is permanently deleted. Make sure a backup exists.</Message>
      <div class="check"><Checkbox v-model="terminate" binary input-id="term" /><label for="term">Terminate active connections</label></div>
    </ConfirmNameDialog>
    <JobDialog :job-id="jobId" @close="jobId = null" @done="jobDone" />
  </div>
</template>
