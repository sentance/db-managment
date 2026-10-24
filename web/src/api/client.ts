// Thin fetch wrapper: same-origin session cookie, CSRF header, typed errors and transparent step-up.

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields?: Record<string, string>,
  ) {
    super(message)
  }
}

type StepUpHandler = () => Promise<boolean>
let stepUpHandler: StepUpHandler | null = null
let unauthorizedHandler: (() => void) | null = null

export function setStepUpHandler(handler: StepUpHandler) {
  stepUpHandler = handler
}

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

function csrfToken(): string | undefined {
  const raw = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return raw ? decodeURIComponent(raw.substring('XSRF-TOKEN='.length)) : undefined
}

async function request<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    cache: 'no-store',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data: unknown = undefined
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!res.ok) {
    const d = (typeof data === 'object' && data !== null ? data : {}) as { code?: string; message?: string; fields?: Record<string, string> }
    const err = new ApiError(res.status, d.code ?? `HTTP_${res.status}`, d.message ?? res.statusText, d.fields)
    if (err.code === 'STEP_UP_REQUIRED' && stepUpHandler && !retried) {
      if (await stepUpHandler()) return request<T>(method, path, body, true)
    }
    if (res.status === 401 && !path.startsWith('/api/auth/') && unauthorizedHandler) unauthorizedHandler()
    throw err
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.fields) return `${e.message}: ${Object.entries(e.fields).map(([k, v]) => `${k} ${v}`).join(', ')}`
    return e.message
  }
  return e instanceof Error ? e.message : String(e)
}
