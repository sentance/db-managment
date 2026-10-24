import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setStepUpHandler } from './client'
import { bytes, duration, statusSeverity } from '@/format'

function response(status: number, body: unknown) {
  return new Response(body === undefined ? '' : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('api client', () => {
  it('sends the CSRF token on mutating requests only', async () => {
    document.cookie = 'XSRF-TOKEN=abc%2D123'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => response(200, { ok: true }))
    await api.get('/api/x')
    await api.post('/api/y', { a: 1 })
    const getHeaders = fetchMock.mock.calls[0][1]!.headers as Record<string, string>
    const postHeaders = fetchMock.mock.calls[1][1]!.headers as Record<string, string>
    expect(getHeaders['X-XSRF-TOKEN']).toBeUndefined()
    expect(postHeaders['X-XSRF-TOKEN']).toBe('abc-123')
  })

  it('asks for step-up once and retries the request', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(response(403, { code: 'STEP_UP_REQUIRED', message: 'confirm' }))
      .mockResolvedValueOnce(response(200, { password: 'p' }))
    const handler = vi.fn().mockResolvedValue(true)
    setStepUpHandler(handler)
    await expect(api.post('/api/roles/x/reveal-password')).resolves.toEqual({ password: 'p' })
    expect(handler).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('surfaces API errors with code and message', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(409, { code: 'CONFLICT', message: 'busy' }))
    await expect(api.post('/api/x')).rejects.toMatchObject({ status: 409, code: 'CONFLICT', message: 'busy' } satisfies Partial<ApiError>)
  })
})

describe('format', () => {
  it('formats sizes and durations', () => {
    expect(bytes(0)).toBe('0 B')
    expect(bytes(1536)).toBe('1.5 KB')
    expect(bytes(-1)).toBe('—')
    expect(duration(59)).toBe('59s')
    expect(duration(3700)).toBe('1h 1m')
  })

  it('maps statuses to severities', () => {
    expect(statusSeverity('RUNNING')).toBe('success')
    expect(statusSeverity('UNREACHABLE')).toBe('danger')
  })
})
