import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import LogicalBackupsPanel from './LogicalBackupsPanel.vue'

vi.mock('@/api/client', () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn() }, errorMessage: (e: Error) => e.message }))
afterEach(() => vi.clearAllMocks())
function render() {
  return mount(LogicalBackupsPanel, { global: { plugins: [PrimeVue], stubs: {
    Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
    RouterLink: { template: '<a><slot /></a>' }, CopyButton: true, JobDialog: true,
  } } })
}
describe('logical backup workflows', () => {
  it('requires verified storage and a successful artifact before exposing the next action', async () => {
    vi.mocked(api.get).mockImplementation(async path => path === '/api/backups/logical' ? [
      { id: 'failed', databaseName: 'app', status: 'FAILED', result: {}, verifiedAt: null },
      { id: 'saved', databaseName: 'app', status: 'SUCCEEDED', result: { sizeBytes: '123' }, verifiedAt: null },
    ] : [])
    const wrapper = render(); await flushPromises()
    expect(wrapper.findAll('button').find(b => b.text() === 'Back up a database')!.attributes('disabled')).toBeDefined()
    const restore = wrapper.findAll('button').filter(b => b.text() === 'Restore to new instance')
    expect(restore[0].attributes('disabled')).toBeDefined()
    expect(restore[1].attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('Never verified')
    wrapper.unmount()
  })
  it('shows a newly generated recovery identity once and clears it when saved', async () => {
    vi.mocked(api.get).mockResolvedValue([])
    vi.mocked(api.post).mockResolvedValue({ destination: { id: 'destination' }, recoveryIdentity: 'AGE-SECRET-KEY-1ONE-TIME' })
    const wrapper = render(); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === 'Add storage')!.trigger('click')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('AGE-SECRET-KEY-1ONE-TIME')
    await wrapper.findAll('button').find(b => b.text() === 'I saved the key')!.trigger('click')
    expect(wrapper.text()).not.toContain('AGE-SECRET-KEY-1ONE-TIME')
    expect(api.post).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it('keeps pending pause distinct from the last confirmed running schedule', async () => {
    const policy = { id: 'policy', name: 'Daily app', database: 'app', dailyTime: '02:00', timezone: 'Europe/Prague', enabled: false, health: 'SYNC_PENDING', syncState: 'PENDING', execution: { enabled: true, nextRunAt: '2026-11-16T01:00:00Z', lastSuccessAt: null } }
    vi.mocked(api.get).mockImplementation(async path => path === '/api/backups/policies' ? [policy] : [])
    const wrapper = render(); await flushPromises()
    expect(wrapper.text()).toContain('Waiting for agent')
    expect(wrapper.text()).toContain('Daily app')
    expect(wrapper.text()).toContain('Pausing takes effect when the agent confirms it')
    expect(wrapper.text()).toContain('None confirmed')
    await wrapper.findAll('button').find(b => b.text() === 'Resume')!.trigger('click'); await flushPromises()
    expect(api.put).toHaveBeenCalledWith('/api/backups/policies/policy/enabled', { enabled: true })
    wrapper.unmount()
  })
})
