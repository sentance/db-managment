import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { AuthStatus } from '@/api/types'

export const useAuth = defineStore('auth', () => {
  const status = ref<AuthStatus | null>(null)
  /** Pending step-up request; the StepUpDialog resolves it. */
  const stepUpPending = ref<((ok: boolean) => void) | null>(null)

  async function refresh() {
    status.value = await api.get<AuthStatus>('/api/auth/status')
    return status.value
  }

  async function login(username: string, password: string, code: string) {
    status.value = await api.post<AuthStatus>('/api/auth/login', { username, password, code })
  }

  async function logout() {
    await api.post('/api/auth/logout')
    status.value = { setupRequired: false, authenticated: false, username: null, stepUpValidUntil: null, recoveryCodesLeft: null }
  }

  async function confirmStepUp(code: string) {
    const r = await api.post<{ validUntil: string }>('/api/auth/step-up', { code })
    if (status.value) status.value.stepUpValidUntil = r.validUntil
  }

  function requestStepUp(): Promise<boolean> {
    return new Promise((resolve) => {
      stepUpPending.value = (ok) => {
        stepUpPending.value = null
        resolve(ok)
      }
    })
  }

  return { status, stepUpPending, refresh, login, logout, confirmStepUp, requestStepUp }
})
