import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/setup', name: 'setup', component: () => import('@/views/SetupView.vue'), meta: { public: true } },
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
    { path: '/servers', name: 'servers', component: () => import('@/views/ServersView.vue') },
    { path: '/instances', name: 'instances', component: () => import('@/views/InstancesView.vue') },
    { path: '/instances/:id', name: 'instance', component: () => import('@/views/InstanceDetailView.vue'), props: true },
    {
      path: '/instances/:id/databases/:name',
      name: 'database',
      component: () => import('@/views/DatabaseDetailView.vue'),
      props: true,
    },
    { path: '/databases', name: 'databases', component: () => import('@/views/DatabasesView.vue') },
    { path: '/roles', name: 'roles', component: () => import('@/views/RolesView.vue') },
    { path: '/backups', name: 'backups', component: () => import('@/views/PlannedView.vue'), props: { feature: 'backups' } },
    { path: '/branches', name: 'branches', component: () => import('@/views/PlannedView.vue'), props: { feature: 'branches' } },
    { path: '/restore', name: 'restore', component: () => import('@/views/PlannedView.vue'), props: { feature: 'restore' } },
    { path: '/activity', name: 'activity', component: () => import('@/views/ActivityView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuth()
  const status = auth.status ?? (await auth.refresh().catch(() => null))
  if (!status) return true
  if (status.setupRequired && to.name !== 'setup') return { name: 'setup' }
  if (!status.setupRequired && to.name === 'setup') return { name: 'login' }
  if (!to.meta.public && !status.authenticated) return { name: 'login', query: { next: to.fullPath } }
  if (to.name === 'login' && status.authenticated) return { name: 'dashboard' }
  return true
})

export default router
