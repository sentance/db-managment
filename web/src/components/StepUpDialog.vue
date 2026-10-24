<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { errorMessage } from '@/api/client'
import { useAuth } from '@/stores/auth'

const auth = useAuth()
const code = ref('')
const error = ref('')
const busy = ref(false)
const visible = computed(() => auth.stepUpPending !== null)

watch(visible, (v) => {
  if (v) {
    code.value = ''
    error.value = ''
  }
})

async function confirm() {
  busy.value = true
  error.value = ''
  try {
    await auth.confirmStepUp(code.value.trim())
    auth.stepUpPending?.(true)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function cancel() {
  auth.stepUpPending?.(false)
}
</script>

<template>
  <Dialog :visible="visible" modal header="Confirm it's you" :closable="false" :style="{ width: '380px' }">
    <form class="form" @submit.prevent="confirm">
      <p class="muted" style="margin: 0">This action is sensitive. Enter a fresh code from your authenticator app; it unlocks sensitive actions for 5 minutes.</p>
      <InputText v-model="code" autofocus inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" />
      <Message v-if="error" severity="error" size="small">{{ error }}</Message>
      <div class="actions" style="justify-content: flex-end">
        <Button label="Cancel" text severity="secondary" @click="cancel" />
        <Button type="submit" label="Confirm" icon="pi pi-shield" :loading="busy" :disabled="code.trim().length !== 6" />
      </div>
    </form>
  </Dialog>
</template>
