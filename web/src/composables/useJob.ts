import { onUnmounted, ref } from 'vue'
import { api } from '@/api/client'
import type { Job, JobDetails, JobEvent } from '@/api/types'

/** Polls a job until it finishes. */
export function useJob() {
  const job = ref<Job | null>(null)
  const events = ref<JobEvent[]>([])
  let timer: ReturnType<typeof setTimeout> | null = null
  let onDone: ((job: Job) => void) | null = null

  async function poll(id: string) {
    try {
      const d = await api.get<JobDetails>(`/api/jobs/${id}`)
      job.value = d.job
      events.value = d.events
      if (d.job.status === 'SUCCEEDED' || d.job.status === 'FAILED') {
        onDone?.(d.job)
        return
      }
    } catch {
      // keep polling; transient errors are expected while services restart
    }
    timer = setTimeout(() => poll(id), 1000)
  }

  function watch(id: string, done?: (job: Job) => void) {
    stop()
    job.value = null
    events.value = []
    onDone = done ?? null
    poll(id)
  }

  function stop() {
    if (timer) clearTimeout(timer)
    timer = null
  }

  onUnmounted(stop)
  return { job, events, watch, stop }
}
