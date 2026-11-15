import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { BackupSource } from '@/api/types'
import BackupsView from './BackupsView.vue'

vi.mock('@/api/client', () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn() }, errorMessage: (e: Error) => e.message }))
afterEach(() => vi.clearAllMocks())

function render() {
  return mount(BackupsView, {
    global: {
      plugins: [PrimeVue],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        LogicalBackupsPanel: true,
        Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
      },
    },
  })
}
const source: BackupSource = {
  instanceId: 'project-id', instanceName: 'project', kind: 'EXTERNAL', provider: null, containerName: null,
  bindingId: null, stanza: null, staleAfterHours: null, inspectionState: 'NOT_CONFIGURED',
  checkedAt: null, lastSuccessAt: null, error: null, backupState: 'UNKNOWN', freshness: 'UNKNOWN',
  encryption: 'UNKNOWN', archiveHealth: 'UNKNOWN', verificationState: 'NEVER_VERIFIED', snapshot: null,
}

describe('backup evidence', () => {
  it('does not claim an unconfigured database has no backups or automatically inspect it', async () => {
    vi.mocked(api.get).mockResolvedValue([source])
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('unknown pgBackRest coverage')
    expect(wrapper.text()).toContain('not configured')
    expect(wrapper.text()).not.toContain('no backups')
    expect(api.post).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('labels failed inspection history as cached and never restore-verified', async () => {
    vi.mocked(api.get).mockResolvedValue([{
      ...source, provider: 'PGBACKREST', bindingId: 'binding', containerName: 'postgres', stanza: 'shared',
      inspectionState: 'UNREACHABLE', error: 'Inspection failed.', lastSuccessAt: '2026-11-14T00:00:00Z',
      snapshot: { catalogue: { stanza: 'shared', statusCode: 0, repositories: [], archives: [], backups: [] }, archiver: null },
    }])
    const wrapper = render()
    await flushPromises()
    const details = wrapper.findAll('button').find(b => b.text() === 'Details')!
    await details.trigger('click')
    expect(wrapper.text()).toContain('unreachable')
    expect(wrapper.text()).toContain('Any history below is cached')
    expect(wrapper.text()).toContain('never verified')
    wrapper.unmount()
  })
})
