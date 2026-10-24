<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Select from 'primevue/select'
import { useToast } from 'primevue/usetoast'
import CopyButton from './CopyButton.vue'
import CredentialsDialog from './CredentialsDialog.vue'
import { api, errorMessage } from '@/api/client'
import type { ConnectionInfo, PasswordResult } from '@/api/types'

const props = defineProps<{ instanceId: string; database: string; roles: string[]; defaultRole?: string; writable: boolean }>()
const toast = useToast()
const role = ref<string | undefined>(props.defaultRole)
const info = ref<ConnectionInfo | null>(null)
const revealed = ref<PasswordResult | null>(null)
const dialog = ref<PasswordResult | null>(null)
const busy = ref(false)

async function load() {
  info.value = null
  const q = role.value ? `?role=${encodeURIComponent(role.value)}` : ''
  try {
    info.value = await api.get<ConnectionInfo>(`/api/instances/${props.instanceId}/databases/${encodeURIComponent(props.database)}/connection${q}`)
    if (!role.value) role.value = info.value.role
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot load connection info', detail: errorMessage(e), life: 5000 })
  }
}

async function reveal(): Promise<PasswordResult> {
  if (revealed.value && revealed.value.role === role.value) return revealed.value
  revealed.value = await api.post<PasswordResult>(`/api/instances/${props.instanceId}/roles/${encodeURIComponent(role.value!)}/reveal-password`)
  return revealed.value
}

async function uriWithPassword(kind: string): Promise<string> {
  try {
    const r = await reveal()
    const conn = r.connections.find((c) => c.database === props.database) ?? r.connections[0]
    const ep = conn?.endpoints.find((e) => e.kind === kind)
    if (!ep) throw new Error('no such endpoint')
    return ep.uri
  } catch (e) {
    throw new Error(errorMessage(e))
  }
}

async function showPassword() {
  try {
    dialog.value = await reveal()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot show password', detail: errorMessage(e), life: 6000 })
  }
}

async function rotate() {
  if (!role.value) return
  busy.value = true
  try {
    const r = await api.post<PasswordResult>(`/api/instances/${props.instanceId}/roles/${encodeURIComponent(role.value)}/rotate-password`)
    revealed.value = r
    dialog.value = r
    await load()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Rotation failed', detail: errorMessage(e), life: 6000 })
  } finally {
    busy.value = false
  }
}

watch(role, (r, old) => {
  if (old !== undefined && r !== old) load()
})
onMounted(load)
</script>

<template>
  <div class="panel">
    <div style="display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap">
      <h2 style="margin: 0">Connection</h2>
      <div class="actions">
        <Select v-model="role" :options="roles" placeholder="Role" style="min-width: 200px" />
        <Button label="Show password" icon="pi pi-eye" size="small" outlined :disabled="!info?.passwordStored" @click="showPassword" />
        <Button v-if="writable" label="Rotate password" icon="pi pi-refresh" size="small" outlined severity="warn" :loading="busy" @click="rotate" />
      </div>
    </div>
    <p v-if="info && !info.passwordStored" class="muted">pgcp does not store a password for <code>{{ info.role }}</code>. Rotate it to issue one.</p>
    <div v-if="info">
      <div v-for="e in info.endpoints" :key="e.kind" style="margin-top: 14px">
        <div style="display: flex; justify-content: space-between; align-items: center; gap: 8px">
          <div><strong>{{ e.kind.replace('_', ' ').toLowerCase() }}</strong> <span class="muted">· {{ e.description }}</span></div>
          <div class="actions">
            <CopyButton v-if="info.passwordStored" :provide="() => uriWithPassword(e.kind)" label="Copy URI" />
            <CopyButton :text="e.uri" label="Copy without password" />
          </div>
        </div>
        <div class="uri">{{ e.uri }}</div>
        <dl class="kv" style="margin-top: 8px">
          <dt>Host</dt><dd class="mono">{{ e.host }}</dd>
          <dt>Port</dt><dd class="mono">{{ e.port }}</dd>
          <dt>Database</dt><dd class="mono">{{ info.database }}</dd>
          <dt>User</dt><dd class="mono">{{ info.role }}</dd>
          <dt>Password</dt><dd class="mono">********</dd>
          <dt>SSL mode</dt><dd class="mono">{{ e.sslMode }}</dd>
        </dl>
      </div>
      <p v-if="info.caCertificateUrl" class="muted" style="margin-top: 12px">
        For <code>sslmode=verify-full</code>, download the <a :href="info.caCertificateUrl">instances CA certificate</a> and pass it as <code>sslrootcert</code>.
      </p>
    </div>
    <CredentialsDialog :visible="dialog !== null" title="Password" :credentials="dialog ? [{ role: dialog.role, kind: 'LOGIN', password: dialog.password }] : []"
      :connections="dialog?.connections ?? []" @close="dialog = null" />
  </div>
</template>
