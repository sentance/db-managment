<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Message from 'primevue/message'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import CopyButton from '@/components/CopyButton.vue'
import { api, errorMessage } from '@/api/client'
import type { Info } from '@/api/types'
import { useAuth } from '@/stores/auth'
import { dateTime } from '@/format'

const auth = useAuth()
const info = ref<Info | null>(null)
const toast = useToast()
const newCodes = ref<string[] | null>(null)

async function regenerate() {
  try {
    newCodes.value = (await api.post<{ recoveryCodes: string[] }>('/api/auth/recovery-codes')).recoveryCodes
    await auth.refresh()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Could not regenerate codes', detail: errorMessage(e), life: 6000 })
  }
}
onMounted(async () => {
  info.value = await api.get<Info>('/api/settings/info')
  await auth.refresh()
})
</script>

<template>
  <div class="page">
    <div class="page-header"><div><h1>Settings</h1><p class="subtitle">Security, environment and integrations.</p></div></div>
    <div class="grid cols-2">
      <div class="panel">
        <h2>Account & security</h2>
        <dl class="kv">
          <dt>Administrator</dt><dd>{{ auth.status?.username }}</dd>
          <dt>Second factor</dt><dd>TOTP (mandatory)</dd>
          <dt>Recovery codes left</dt><dd>{{ auth.status?.recoveryCodesLeft }}</dd>
          <dt>Sensitive actions</dt><dd>{{ auth.status?.stepUpValidUntil ? 'unlocked until ' + dateTime(auth.status.stepUpValidUntil) : 'locked (confirm with authenticator when needed)' }}</dd>
        </dl>
        <Message v-if="(auth.status?.recoveryCodesLeft ?? 10) < 3" severity="warn" :closable="false" style="margin-top: 12px">Few recovery codes left. Generate a new set.</Message>
        <Button label="Generate new recovery codes" icon="pi pi-refresh" size="small" outlined style="margin-top: 12px" @click="regenerate" />
        <div v-if="newCodes" style="margin-top: 12px">
          <Message severity="warn" :closable="false">Old codes no longer work. Store these offline now; they are not shown again.</Message>
          <div class="uri" style="columns: 2; margin-top: 8px"><div v-for="c in newCodes" :key="c">{{ c }}</div></div>
          <CopyButton :text="newCodes.join('\n')" label="Copy all" />
        </div>
      </div>
      <div class="panel">
        <h2>Environment</h2>
        <dl class="kv">
          <dt>Public host</dt><dd>{{ info?.publicHost || 'not set (PGCP_PUBLIC_HOST)' }}</dd>
          <dt>SQL editor</dt><dd><a v-if="info?.sqlEditorUrl" :href="info.sqlEditorUrl" target="_blank" rel="noopener noreferrer">{{ info.sqlEditorUrl }}</a><span v-else>not set (PGCP_SQL_EDITOR_URL)</span></dd>
          <dt>PostgreSQL versions</dt><dd>{{ info?.supportedPgMajors.join(', ') }}</dd>
          <dt>Instances CA</dt><dd><a href="/api/tls/instances-ca">Download certificate</a></dd>
        </dl>
      </div>
      <div class="panel">
        <h2>Backup storage (S3 / R2)</h2>
        <p class="muted">Phase 2: pgBackRest repositories on AWS S3, Cloudflare R2, Hetzner Object Storage or any S3-compatible endpoint.</p>
      </div>
      <div class="panel">
        <h2>Notifications (Telegram)</h2>
        <p class="muted">Phase 2: alerts for failed or stale backups, WAL archiving interruptions, low disk space and unavailable databases.</p>
      </div>
    </div>
  </div>
</template>
