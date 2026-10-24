<script setup lang="ts">
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Message from 'primevue/message'
import CopyButton from './CopyButton.vue'
import type { ConnectionInfo, IssuedCredential } from '@/api/types'

defineProps<{ visible: boolean; title?: string; credentials: IssuedCredential[]; connections: ConnectionInfo[] }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Dialog :visible="visible" modal :header="title ?? 'Credentials'" :style="{ width: '720px' }" :closable="false">
    <Message severity="warn" :closable="false">
      Copy these now. Passwords are stored encrypted; showing them again requires re-confirming with your authenticator.
    </Message>
    <div v-for="c in credentials" :key="c.role" style="margin-top: 14px">
      <div style="display: flex; justify-content: space-between; align-items: center">
        <strong>{{ c.role }} <span class="muted">· {{ c.kind }}</span></strong>
        <CopyButton :text="c.password" label="Copy password" />
      </div>
      <div class="uri">{{ c.password }}</div>
    </div>
    <div v-for="conn in connections" :key="conn.role + conn.database" style="margin-top: 16px">
      <h3 style="margin: 0 0 6px; font-size: 13px">Connection strings · {{ conn.role }} → {{ conn.database }}</h3>
      <div v-for="e in conn.endpoints" :key="e.kind" style="margin-bottom: 8px">
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span class="muted">{{ e.description }}</span>
          <CopyButton :text="e.uri" />
        </div>
        <div class="uri">{{ e.uri }}</div>
      </div>
    </div>
    <template #footer>
      <Button label="I have saved them" icon="pi pi-check" @click="emit('close')" />
    </template>
  </Dialog>
</template>
