<template>
  <div class="app-shell">
    <Sidebar />
    <main class="app-main">
      <router-view v-slot="{ Component }">
        <transition name="shell-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import Sidebar from '@/components/Sidebar.vue'
</script>

<style>
.app-shell {
  display: flex;
  height: 100dvh;
  min-height: 100dvh;
  padding: var(--shell-gap);
  gap: var(--shell-gap);
  overflow: hidden;
}

.app-main {
  flex: 1;
  margin-left: calc(var(--sidebar-width) + var(--shell-gap));
  min-width: 0;
  height: calc(100dvh - (var(--shell-gap) * 2));
  min-height: 0;
  overflow: hidden;
  border-radius: var(--radius-xl);
}

.shell-fade-enter-active,
.shell-fade-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.shell-fade-enter-from,
.shell-fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

@media (max-width: 1100px) {
  .app-shell {
    flex-direction: column;
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }

  .app-main {
    margin-left: 0;
    height: auto;
    min-height: 0;
    overflow: visible;
  }
}
</style>
