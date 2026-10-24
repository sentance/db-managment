<script setup lang="ts">
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useAuth } from '@/stores/auth'

const auth = useAuth()
const router = useRouter()

const nav = [
  { to: '/', label: 'Dashboard', icon: 'pi pi-th-large' },
  { to: '/servers', label: 'Servers', icon: 'pi pi-server' },
  { to: '/instances', label: 'Instances', icon: 'pi pi-box' },
  { to: '/databases', label: 'Databases', icon: 'pi pi-database' },
  { to: '/roles', label: 'Users & Permissions', icon: 'pi pi-users' },
  { to: '/backups', label: 'Backups', icon: 'pi pi-cloud-upload', planned: true },
  { to: '/branches', label: 'Branches', icon: 'pi pi-share-alt', planned: true },
  { to: '/restore', label: 'Restore', icon: 'pi pi-history', planned: true },
  { to: '/activity', label: 'Activity & Jobs', icon: 'pi pi-list' },
  { to: '/settings', label: 'Settings', icon: 'pi pi-cog' },
]

async function logout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <span class="logo">pg</span>
        <div>
          <div class="brand-name">pgcp</div>
          <div class="brand-sub">PostgreSQL Control Plane</div>
        </div>
      </div>
      <nav>
        <RouterLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-item" :class="{ planned: item.planned }"
          :exact-active-class="item.to === '/' ? 'active' : ''" :active-class="item.to === '/' ? '' : 'active'">
          <i :class="item.icon" />
          <span>{{ item.label }}</span>
          <small v-if="item.planned">soon</small>
        </RouterLink>
      </nav>
      <div class="account">
        <div class="who"><i class="pi pi-user" /> {{ auth.status?.username }}</div>
        <Button label="Sign out" icon="pi pi-sign-out" size="small" text severity="secondary" @click="logout" />
      </div>
    </aside>
    <main class="content"><slot /></main>
  </div>
</template>

<style scoped>
.layout { display: flex; min-height: 100vh; }
.sidebar { width: 236px; flex-shrink: 0; background: var(--app-sidebar); color: var(--app-sidebar-text); display: flex; flex-direction: column; position: sticky; top: 0; height: 100vh; }
.brand { display: flex; gap: 10px; align-items: center; padding: 18px 16px 14px; }
.logo { width: 34px; height: 34px; border-radius: 8px; background: var(--app-accent); color: #fff; display: grid; place-items: center; font-weight: 700; }
.brand-name { color: #fff; font-weight: 650; }
.brand-sub { font-size: 11px; opacity: 0.7; }
nav { display: flex; flex-direction: column; padding: 8px; gap: 2px; flex: 1; overflow-y: auto; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 6px; color: var(--app-sidebar-text); text-decoration: none; }
.nav-item:hover { background: var(--app-sidebar-active); text-decoration: none; }
.nav-item.active { background: var(--app-sidebar-active); color: #fff; box-shadow: inset 2px 0 0 var(--app-accent); }
.nav-item.planned { opacity: 0.6; }
.nav-item small { margin-left: auto; font-size: 10px; text-transform: uppercase; opacity: 0.8; }
.account { border-top: 1px solid #1d2733; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; }
.who { font-size: 13px; display: flex; gap: 6px; align-items: center; }
.content { flex: 1; min-width: 0; }
@media (max-width: 800px) {
  .layout { flex-direction: column; }
  .sidebar { width: 100%; height: auto; position: static; }
  nav { flex-direction: row; flex-wrap: wrap; }
}
</style>
