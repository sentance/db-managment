<script setup lang="ts">
import LogicalBackupsPanel from '@/components/LogicalBackupsPanel.vue'
</script>
<template><div class="page"><div class="page-header"><div><h1>Restore</h1><p class="subtitle">Recover encrypted logical backups into new isolated PostgreSQL instances.</p></div></div><LogicalBackupsPanel restore-only /></div></template>
