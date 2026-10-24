export function bytes(n: number | null | undefined): string {
  if (n == null || n < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${units[i]}`
}

export function duration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  const s = Math.max(0, Math.floor(seconds))
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`
  if (s < 86400) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
  return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return '—'
  const s = (Date.now() - new Date(iso).getTime()) / 1000
  if (s < 5) return 'just now'
  return `${duration(s)} ago`
}

export function percent(part: number, total: number): number {
  return total > 0 ? Math.round((part / total) * 1000) / 10 : 0
}

export type Severity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast'

export function statusSeverity(status: string): Severity {
  switch (status) {
    case 'RUNNING':
    case 'SUCCEEDED':
    case 'SUCCESS':
      return 'success'
    case 'PROVISIONING':
    case 'PENDING':
    case 'DELETING':
      return 'info'
    case 'STOPPED':
    case 'UNHEALTHY':
    case 'WARNING':
      return 'warn'
    case 'FAILED':
    case 'UNREACHABLE':
    case 'CRITICAL':
    case 'FAILURE':
      return 'danger'
    default:
      return 'secondary'
  }
}
