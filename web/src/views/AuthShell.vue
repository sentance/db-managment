<template>
  <div class="auth">
    <div class="card">
      <div class="brand"><span class="logo">pg</span><div><strong>pgcp</strong><div class="muted">PostgreSQL Control Plane</div></div></div>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.card { width: 100%; max-width: 420px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 12px; padding: 28px; }
.brand { display: flex; gap: 12px; align-items: center; margin-bottom: 22px; }
.logo { width: 38px; height: 38px; border-radius: 9px; background: var(--app-accent); color: #fff; display: grid; place-items: center; font-weight: 700; }
</style>
