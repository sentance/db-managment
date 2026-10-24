<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import QRCode from 'qrcode'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import AuthShell from './AuthShell.vue'
import CopyButton from '@/components/CopyButton.vue'
import { api, errorMessage } from '@/api/client'
import { useAuth } from '@/stores/auth'

const router = useRouter()
const auth = useAuth()
const step = ref<'account' | 'totp' | 'codes'>('account')
const form = ref({ token: '', username: 'admin', password: '', confirm: '' })
const secret = ref('')
const qr = ref('')
const code = ref('')
const recovery = ref<string[]>([])
const error = ref('')
const busy = ref(false)

async function createAccount() {
  error.value = ''
  if (form.value.password !== form.value.confirm) {
    error.value = 'passwords do not match'
    return
  }
  busy.value = true
  try {
    const r = await api.post<{ totpSecret: string; otpauthUri: string }>('/api/auth/setup', {
      token: form.value.token.trim(), username: form.value.username, password: form.value.password,
    })
    secret.value = r.totpSecret
    qr.value = await QRCode.toDataURL(r.otpauthUri, { margin: 1, width: 220 })
    step.value = 'totp'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function confirmTotp() {
  busy.value = true
  error.value = ''
  try {
    const r = await api.post<{ username: string; recoveryCodes: string[] }>('/api/auth/setup/totp', { code: code.value.trim() })
    recovery.value = r.recoveryCodes
    step.value = 'codes'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function finish() {
  await auth.refresh()
  router.replace('/')
}
</script>

<template>
  <AuthShell>
    <form v-if="step === 'account'" class="form" @submit.prevent="createAccount">
      <h2 style="margin: 0">Initial setup</h2>
      <p class="muted" style="margin: 0">
        On the server run <code>docker compose exec control-plane cat /var/lib/pgcp-cp/setup-token</code> and paste the token.
      </p>
      <div class="field"><label>Setup token</label><InputText v-model="form.token" autocomplete="off" /></div>
      <div class="field"><label>Admin username</label><InputText v-model="form.username" autocomplete="username" /></div>
      <div class="field"><label>Password (12+ characters)</label><Password v-model="form.password" toggle-mask fluid autocomplete="new-password" /></div>
      <div class="field"><label>Repeat password</label><Password v-model="form.confirm" :feedback="false" fluid autocomplete="new-password" /></div>
      <Message v-if="error" severity="error" size="small">{{ error }}</Message>
      <Button type="submit" label="Continue" :loading="busy" :disabled="!form.token || form.password.length < 12" />
    </form>

    <form v-else-if="step === 'totp'" class="form" @submit.prevent="confirmTotp">
      <h2 style="margin: 0">Two-factor authentication</h2>
      <p class="muted" style="margin: 0">Scan with an authenticator app (Aegis, 1Password, Google Authenticator), then enter the code.</p>
      <img :src="qr" alt="TOTP QR code" style="align-self: center; border-radius: 8px; background: #fff" />
      <div style="display: flex; align-items: center; gap: 6px"><code style="word-break: break-all">{{ secret }}</code><CopyButton :text="secret" /></div>
      <InputText v-model="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" autofocus />
      <Message v-if="error" severity="error" size="small">{{ error }}</Message>
      <Button type="submit" label="Verify and finish" :loading="busy" :disabled="code.trim().length !== 6" />
    </form>

    <div v-else class="form">
      <h2 style="margin: 0">Recovery codes</h2>
      <Message severity="warn" :closable="false">Each code works once if you lose your authenticator. Store them offline now; they are not shown again.</Message>
      <div class="uri" style="columns: 2">
        <div v-for="c in recovery" :key="c">{{ c }}</div>
      </div>
      <CopyButton :text="recovery.join('\n')" label="Copy all" />
      <Button label="Open the panel" icon="pi pi-arrow-right" @click="finish" />
    </div>
  </AuthShell>
</template>
