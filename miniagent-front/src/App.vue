<template>
  <div class="app-shell">
    <Sidebar :collapsed="sidebarCollapsed" @toggle="toggleSidebar" />
    <main class="app-main" :class="{ collapsed: sidebarCollapsed }">
      <router-view v-slot="{ Component }">
        <transition name="shell-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import Sidebar from '@/components/Sidebar.vue'

const SIDEBAR_STORAGE_KEY = 'miniagent.sidebar.collapsed'
const sidebarCollapsed = ref(false)

const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

onMounted(() => {
  sidebarCollapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'true'
  
  // Performance detection - add reduced-animation class for low-perf devices
  const isLowPerf = navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (isLowPerf || prefersReduced) {
    document.documentElement.classList.add('reduced-animation')
  }
})

watch(sidebarCollapsed, (value) => {
  window.localStorage.setItem(SIDEBAR_STORAGE_KEY, String(value))
})
</script>

<style>
.app-shell {
  position: relative;
  display: flex;
  height: 100dvh;
  min-height: 100dvh;
  padding: var(--shell-gap);
  gap: var(--shell-gap);
  overflow: hidden;
}

.app-shell::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  background: 
    radial-gradient(circle at 20% 20%, rgba(123, 211, 255, 0.15), transparent 40%),
    radial-gradient(circle at 80% 30%, rgba(91, 108, 255, 0.12), transparent 35%),
    radial-gradient(circle at 50% 80%, rgba(163, 130, 255, 0.1), transparent 40%);
  background-size: 400% 400%;
  animation: liquid-flow var(--duration-liquid, 20s) ease-in-out infinite;
  pointer-events: none;
}

.app-main {
  position: relative;
  z-index: 1;
  flex: 1;
  margin-left: calc(var(--sidebar-width) + var(--shell-gap));
  min-width: 0;
  height: calc(100dvh - (var(--shell-gap) * 2));
  min-height: 0;
  overflow: hidden;
  border-radius: var(--radius-xl);
  transition: margin-left var(--transition-normal);
}

.app-main.collapsed {
  margin-left: calc(84px + var(--shell-gap));
}

.shell-fade-enter-active,
.shell-fade-leave-active {
  transition:
    opacity 260ms var(--spring-snappy, cubic-bezier(0.22, 1.2, 0.36, 1)),
    transform 260ms var(--spring-snappy, cubic-bezier(0.22, 1.2, 0.36, 1));
}

.shell-fade-enter-from {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}

.shell-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.99);
}

@media (prefers-reduced-motion: reduce) {
  .app-shell::before {
    animation: none;
  }
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
