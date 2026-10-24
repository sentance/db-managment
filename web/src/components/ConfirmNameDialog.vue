<script setup lang="ts">
import { ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

const props = defineProps<{ visible: boolean; title: string; name: string; confirmLabel?: string; busy?: boolean }>()
const emit = defineEmits<{ confirm: [name: string]; cancel: [] }>()
const typed = ref('')
watch(() => props.visible, () => (typed.value = ''))
</script>

<template>
  <Dialog :visible="visible" modal :header="title" :style="{ width: '460px' }" @update:visible="(v) => !v && emit('cancel')">
    <form class="form" @submit.prevent="typed === name && emit('confirm', typed)">
      <slot />
      <div class="field">
        <label>Type <code>{{ name }}</code> to confirm</label>
        <InputText v-model="typed" autofocus autocomplete="off" />
      </div>
      <div class="actions" style="justify-content: flex-end">
        <Button label="Cancel" text severity="secondary" @click="emit('cancel')" />
        <Button type="submit" :label="confirmLabel ?? 'Delete'" severity="danger" :disabled="typed !== name" :loading="busy" />
      </div>
    </form>
  </Dialog>
</template>
