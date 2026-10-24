<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Select from 'primevue/select'
import RolesPanel from '@/components/RolesPanel.vue'
import { api } from '@/api/client'
import type { Instance } from '@/api/types'

const route = useRoute()
const router = useRouter()
const instances = ref<Instance[]>([])
const selected = ref<Instance | null>(null)

onMounted(async () => {
  instances.value = await api.get<Instance[]>('/api/instances')
  const wanted = route.query.instance
  selected.value = instances.value.find((i) => i.id === wanted) ?? instances.value[0] ?? null
})
watch(selected, (i) => i && router.replace({ query: { instance: i.id } }))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>Users & Permissions</h1><p class="subtitle">PostgreSQL roles, passwords and database access, per instance.</p></div>
      <Select v-model="selected" :options="instances" option-label="name" placeholder="Instance" style="min-width: 240px" />
    </div>
    <RolesPanel v-if="selected" :key="selected.id" :instance="selected" />
    <div v-else class="panel empty">No instances yet.</div>
  </div>
</template>
