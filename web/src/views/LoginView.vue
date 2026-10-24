<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import AuthShell from './AuthShell.vue'
import { errorMessage } from '@/api/client'
import { useAuth } from '@/stores/auth'

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const code = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await auth.login(username.value, password.value, code.value.trim())
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : '/'
    router.replace(next)
  } catch (e) {
    error.value = errorMessage(e)
    code.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthShell>
    <form class="form" @submit.prevent="submit">
      <div class="field"><label>Username</label><InputText v-model="username" autocomplete="username" autofocus /></div>
      <div class="field"><label>Password</label><Password v-model="password" :feedback="false" toggle-mask input-class="w-full" autocomplete="current-password" fluid /></div>
      <div class="field">
        <label>Authenticator code</label>
        <InputText v-model="code" inputmode="numeric" autocomplete="one-time-code" placeholder="123456 or recovery code" />
      </div>
      <Message v-if="error" severity="error" size="small">{{ error }}</Message>
      <Button type="submit" label="Sign in" icon="pi pi-sign-in" :loading="busy" :disabled="!username || !password || !code" />
    </form>
  </AuthShell>
</template>
