<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Toast from 'primevue/toast'
import AppLayout from '@/components/AppLayout.vue'
import StepUpDialog from '@/components/StepUpDialog.vue'

const route = useRoute()
const bare = computed(() => route.meta.public === true)
</script>

<template>
  <Toast position="top-right" />
  <StepUpDialog />
  <RouterView v-if="bare" />
  <AppLayout v-else>
    <RouterView :key="route.fullPath" />
  </AppLayout>
</template>
