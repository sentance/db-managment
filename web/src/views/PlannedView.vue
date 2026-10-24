<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ feature: 'backups' | 'branches' | 'restore' }>()
const content = {
  backups: {
    title: 'Backups', phase: 'Phase 2',
    points: ['pgBackRest full / differential / incremental backups with WAL archiving', 'Logical pg_dump backups per database',
      'S3-compatible storage (AWS S3, Cloudflare R2, Hetzner)', 'Schedules run by the agent even when the panel is down',
      'Retention policies, encryption and integrity checks', 'Automatic restore verification in an isolated container'],
  },
  restore: {
    title: 'Restore', phase: 'Phase 2',
    points: ['Restore a logical backup into a new database', 'Restore a physical backup or PITR target time into a new instance',
      'Disk-space estimate before starting', 'Never overwrites production by default'],
  },
  branches: {
    title: 'Branches', phase: 'Phase 3–4',
    points: ['Basic branches: full logical copy via pg_dump / pg_restore (schema-only optional)', 'Separate connection strings and TTL cleanup',
      'Fast copy-on-write branches through DBLab Engine on ZFS hosts (optional)'],
  },
}
const c = computed(() => content[props.feature])
</script>

<template>
  <div class="page">
    <div class="page-header"><div><h1>{{ c.title }}</h1><p class="subtitle">Planned for {{ c.phase }}</p></div></div>
    <div class="panel">
      <ul style="margin: 0; padding-left: 18px; line-height: 1.9"><li v-for="p in c.points" :key="p">{{ p }}</li></ul>
    </div>
  </div>
</template>
