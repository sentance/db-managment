import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import TelegramSettings from './TelegramSettings.vue'
vi.mock('@/api/client', () => ({ api: { get: vi.fn(), put: vi.fn() }, errorMessage: (e: Error) => e.message }))
afterEach(() => vi.clearAllMocks())
it('preserves an existing bot token unless replaced, then clears its input after saving', async () => {
  vi.mocked(api.get).mockImplementation(async path => path === '/api/notifications/telegram' ? { enabled: false, configured: true, chatId: '-10042', revision: 1 } : [])
  vi.mocked(api.put).mockResolvedValue({ enabled: false, configured: true, chatId: '-10042', revision: 2 })
  const wrapper = mount(TelegramSettings, { global: { plugins: [PrimeVue] } }); await flushPromises()
  const input = wrapper.find<HTMLInputElement>('input[type="password"]')
  expect(input.element.value).toBe('')
  expect(wrapper.text()).toContain('leave empty to keep it')
  await input.setValue('123456:PrivateTokenForReplacement42')
  await wrapper.find('form').trigger('submit'); await flushPromises()
  expect(api.put).toHaveBeenCalledWith('/api/notifications/telegram', expect.objectContaining({ chatId: '-10042', token: '123456:PrivateTokenForReplacement42' }))
  expect(input.element.value).toBe('')
  wrapper.unmount()
})
