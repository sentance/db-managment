<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { api, errorMessage } from '@/api/client'
import type { DiscoveredContainer, ServerStatus } from '@/api/types'
import { ago, bytes } from '@/format'

const router = useRouter()
const servers = ref<ServerStatus[]>([])
const containers = ref<Record<string, DiscoveredContainer[]>>({})
const error = ref('')

async function load() {
  try {
    servers.value = await api.get<ServerStatus[]>('/api/servers')
    for (const s of servers.value) {
      if (s.agentReachable) containers.value[s.server.id] = await api.get<DiscoveredContainer[]>(`/api/servers/${s.server.id}/containers`)
    }
  } catch (e) {
    error.value = errorMessage(e)
  }
}

function register(c: DiscoveredContainer) {
  router.push({ name: 'instances', query: { register: c.container.name, port: '5432' } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>Servers</h1><p class="subtitle">Hosts with a management agent. Discovery is read-only.</p></div>
      <Button icon="pi pi-refresh" text @click="load" />
    </div>
    <Message v-if="error" severity="error">{{ error }}</Message>
    <div v-for="s in servers" :key="s.server.id">
      <div class="panel">
        <h2>{{ s.server.name }}</h2>
        <Message v-if="!s.agentReachable" severity="error" :closable="false">Agent unreachable: {{ s.error }}</Message>
        <dl v-if="s.facts" class="kv">
          <dt>Hostname</dt><dd>{{ s.facts.hostname }}</dd>
          <dt>OS / kernel</dt><dd>{{ s.facts.os }} · {{ s.facts.kernel }}</dd>
          <dt>CPU</dt><dd>{{ s.facts.cpuCores }} cores<span v-if="s.metrics"> · {{ s.metrics.cpuPercent }}% used</span></dd>
          <dt>Memory</dt><dd>{{ bytes(s.facts.memTotalBytes) }}<span v-if="s.metrics"> · {{ bytes(s.metrics.memAvailableBytes) }} available</span> · swap {{ bytes(s.facts.swapTotalBytes) }}</dd>
          <dt>Disks</dt><dd><div v-for="d in s.facts.disks" :key="d.label">{{ d.label }}: {{ bytes(d.freeBytes) }} free of {{ bytes(d.totalBytes) }}</div></dd>
          <dt>Filesystems</dt><dd>{{ s.facts.filesystems.join(', ') || '—' }}
            <span class="muted">· copy-on-write branching {{ s.facts.filesystems.includes('zfs') ? 'possible (ZFS)' : 'unavailable (no ZFS)' }}</span></dd>
          <dt>Docker</dt><dd>{{ s.facts.dockerVersion }}</dd>
          <dt>Agent</dt><dd class="mono">{{ s.server.agentUrl }} · seen {{ ago(s.server.lastSeenAt) }}</dd>
        </dl>
      </div>
      <div v-if="containers[s.server.id]" class="panel">
        <h2>PostgreSQL containers on this host</h2>
        <DataTable :value="containers[s.server.id]" size="small">
          <Column header="Container"><template #body="{ data }"><code>{{ data.container.name }}</code></template></Column>
          <Column header="Version"><template #body="{ data }">{{ data.container.pgVersion ?? data.container.pgMajor }}</template></Column>
          <Column header="Image"><template #body="{ data }"><span class="mono">{{ data.container.image }}</span></template></Column>
          <Column header="libc"><template #body="{ data }">{{ data.container.libc }}</template></Column>
          <Column header="State"><template #body="{ data }">{{ data.container.state }}<span v-if="data.container.health"> ({{ data.container.health }})</span></template></Column>
          <Column header="Networks"><template #body="{ data }"><span class="mono">{{ data.container.networks.join(', ') }}</span></template></Column>
          <Column header="">
            <template #body="{ data }">
              <Tag v-if="data.container.managed" value="managed by pgcp" severity="success" />
              <Tag v-else-if="data.registered" value="registered" severity="info" />
              <Tag v-else-if="data.container.labels['coolify.managed']" value="coolify" severity="secondary" style="margin-right: 6px" />
              <Button v-if="!data.container.managed && !data.registered" label="Register" size="small" text @click="register(data)" />
            </template>
          </Column>
        </DataTable>
      </div>
    </div>
  </div>
</template>
