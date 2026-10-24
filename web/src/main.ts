import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import { definePreset } from '@primeuix/themes'
import 'primeicons/primeicons.css'
import './styles/app.css'
import App from './App.vue'
import router from './router'
import { setStepUpHandler, setUnauthorizedHandler } from './api/client'
import { useAuth } from './stores/auth'

const Preset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{emerald.50}', 100: '{emerald.100}', 200: '{emerald.200}', 300: '{emerald.300}', 400: '{emerald.400}',
      500: '{emerald.500}', 600: '{emerald.600}', 700: '{emerald.700}', 800: '{emerald.800}', 900: '{emerald.900}',
      950: '{emerald.950}',
    },
  },
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(PrimeVue, { theme: { preset: Preset, options: { darkModeSelector: 'system' } } })
app.use(ToastService)
app.directive('tooltip', Tooltip)

const auth = useAuth()
setStepUpHandler(() => auth.requestStepUp())
setUnauthorizedHandler(() => {
  auth.status = null
  if (router.currentRoute.value.name !== 'login') router.push({ name: 'login', query: { next: router.currentRoute.value.fullPath } })
})

app.mount('#app')
