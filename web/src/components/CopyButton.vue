<script setup lang="ts">
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ text?: string; provide?: () => Promise<string>; label?: string }>()
const toast = useToast()

async function copy() {
  try {
    const value = props.provide ? await props.provide() : props.text ?? ''
    await navigator.clipboard.writeText(value)
    toast.add({ severity: 'success', summary: 'Copied to clipboard', life: 1800 })
  } catch (e) {
    if (e instanceof Error && e.message) toast.add({ severity: 'error', summary: 'Copy failed', detail: e.message, life: 4000 })
  }
}
</script>

<template>
  <Button :label="label" icon="pi pi-copy" size="small" text @click="copy" v-tooltip.top="label ? undefined : 'Copy'" />
</template>
