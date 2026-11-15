<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import Message from 'primevue/message'
import { api, errorMessage } from '@/api/client'
import { dateTime } from '@/format'
interface Alert { id: string; kind: string; message: string; active: boolean; changedAt: string }
const alerts = ref<Alert[]>([]), error = ref('')
let timer: ReturnType<typeof setInterval>
async function load() {
  try { alerts.value = await api.get<Alert[]>('/api/backups/alerts'); error.value = '' }
  catch (e) { error.value = errorMessage(e) }
}
onMounted(() => { load(); timer = setInterval(load, 10000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <div class="panel">
    <h2>Backup alerts</h2>
    <Message v-if="error" severity="error">{{ error }}</Message>
    <p v-if="!alerts.length" class="muted">No recorded backup alerts. This does not confirm backup coverage. Configure Telegram delivery in <RouterLink to="/settings">Settings</RouterLink>.</p>
    <div v-for="alert in alerts" :key="alert.id" style="margin-top: 8px">
      <Message :severity="alert.active ? 'warn' : 'success'">{{ alert.active ? 'Active' : 'Resolved' }} · {{ alert.message }} <span class="muted">{{ dateTime(alert.changedAt) }}</span></Message>
    </div>
  </div>
</template>
