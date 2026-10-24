<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { api, errorMessage } from '@/api/client'
import type { DatabaseView, Instance } from '@/api/types'
import { bytes } from '@/format'

interface Row extends DatabaseView { instance: Instance }

const router = useRouter()
const rows = ref<Row[]>([])
const errors = ref<string[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const instances = await api.get<Instance[]>('/api/instances')
    const results = await Promise.allSettled(instances.map((i) => api.get<DatabaseView[]>(`/api/instances/${i.id}/databases`)))
    results.forEach((r, idx) => {
      if (r.status === 'fulfilled') rows.value.push(...r.value.filter((d) => !d.system).map((d) => ({ ...d, instance: instances[idx] })))
      else errors.value.push(`${instances[idx].name}: ${errorMessage(r.reason)}`)
    })
  } catch (e) {
    errors.value.push(errorMessage(e))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header"><div><h1>Databases</h1><p class="subtitle">User databases across all instances. Create new ones from an instance.</p></div></div>
    <Message v-for="e in errors" :key="e" severity="warn">{{ e }}</Message>
    <div class="panel">
      <DataTable :value="rows" :loading="loading" size="small" row-hover style="cursor: pointer" sort-field="name" :sort-order="1"
        @row-click="(e) => router.push({ name: 'database', params: { id: e.data.instance.id, name: e.data.name } })">
        <Column field="name" header="Database" sortable><template #body="{ data }"><code>{{ data.name }}</code>
          <Tag v-if="data.managed" value="pgcp" severity="success" style="margin-left: 6px" /></template></Column>
        <Column header="Instance"><template #body="{ data }">{{ data.instance.name }}</template></Column>
        <Column field="owner" header="Owner" />
        <Column field="sizeBytes" header="Size" sortable><template #body="{ data }">{{ bytes(data.sizeBytes) }}</template></Column>
        <Column field="connections" header="Connections" sortable />
        <template #empty><div class="empty">No user databases yet.</div></template>
      </DataTable>
    </div>
  </div>
</template>
