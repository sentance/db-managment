<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Password from 'primevue/password'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { api, errorMessage } from '@/api/client'
import { dateTime } from '@/format'
interface Settings { enabled: boolean; configured: boolean; chatId: string; revision: number }
interface Delivery { id: string; status: string; attempts: number; nextAttemptAt: string; sentAt: string | null; lastError: string | null }
const form = reactive({ enabled: false, chatId: '', token: '' }), configured = ref(false), busy = ref(false), loaded = ref(false), error = ref(''), saved = ref(false)
const deliveries = ref<Delivery[]>([])
let timer: ReturnType<typeof setInterval>
let mounted = true
async function history() {
  try { deliveries.value = await api.get<Delivery[]>('/api/notifications/deliveries') }
  catch (e) { error.value = errorMessage(e) }
}
async function save() {
  busy.value = true; error.value = ''; saved.value = false
  try {
    const result = await api.put<Settings>('/api/notifications/telegram', { ...form })
    configured.value = result.configured; form.token = ''; saved.value = true; await history()
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
onMounted(async () => {
  try {
    const result = await api.get<Settings>('/api/notifications/telegram')
    if (!mounted) return
    Object.assign(form, { enabled: result.enabled, chatId: result.chatId }); configured.value = result.configured; loaded.value = true
    await history(); if (mounted) timer = setInterval(history, 10000)
  } catch (e) { error.value = errorMessage(e) }
})
onUnmounted(() => { mounted = false; clearInterval(timer); form.token = '' })
</script>
<template>
  <div class="panel">
    <h2>Backup notifications (Telegram)</h2>
    <p class="muted">Receive backup, freshness, agent, retention and restore alerts, followed by resolution updates. The panel must be running to send notifications.</p>
    <form v-if="loaded" class="form" @submit.prevent="save">
      <label><Checkbox v-model="form.enabled" binary /> Send new alert transitions to Telegram</label>
      <div class="field"><label>Numeric chat ID</label><InputText v-model="form.chatId" placeholder="-1001234567890" autocomplete="off" /></div>
      <div class="field"><label>{{ configured ? 'Replace bot token (leave empty to keep it)' : 'Bot token' }}</label><Password v-model="form.token" :feedback="false" toggle-mask autocomplete="new-password" /></div>
      <p class="muted">Add your bot to the chat and allow it to send messages. Saving changed settings cancels pending deliveries for the previous settings; a send already in progress may finish.</p>
      <Button label="Save notifications" type="submit" :loading="busy" />
    </form>
    <Message v-if="error" severity="error">{{ error }}</Message><Message v-if="saved" severity="success">Notification settings saved. The stored bot token is never returned by the API.</Message>
    <details v-if="deliveries.length" style="margin-top: 16px"><summary>Recent deliveries</summary>
      <div v-for="delivery in deliveries.slice(0, 20)" :key="delivery.id" style="margin-top: 8px">
        {{ delivery.status }} · {{ delivery.attempts }} attempts · {{ dateTime(delivery.sentAt ?? delivery.nextAttemptAt) }}
        <p v-if="delivery.lastError" class="muted">{{ delivery.lastError }}</p>
      </div>
    </details>
  </div>
</template>
