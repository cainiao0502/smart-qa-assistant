<template>
  <div class="app-shell" :class="{ 'is-auth': isAuthLayout }">
    <Sidebar v-if="!isAuthLayout" ref="sidebarRef" :collapsed="sidebarCollapsed" @toggle="toggleSidebar" />
    <main ref="mainRef" class="app-main" :class="{ collapsed: sidebarCollapsed, 'is-auth': isAuthLayout }">
      <router-view v-slot="{ Component }">
        <transition name="shell-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import gsap from 'gsap'
import Sidebar from '@/components/Sidebar.vue'

const route = useRoute()
const isAuthLayout = computed(() => Boolean(route.meta?.public))

const SIDEBAR_STORAGE_KEY = 'miniagent.sidebar.collapsed'
const sidebarCollapsed = ref(false)
const sidebarRef = ref(null)
const mainRef = ref(null)
let collapseTween = null

/* 与 main.css 的 --sidebar-width / --shell-gap 严格同源（P0 修复：原 260/16 与 CSS 248/12 不一致，
   导致首次折叠动画后主区 margin-left 跳 16px） */
const SIDEBAR_EXPANDED_WIDTH = 248
const SIDEBAR_COLLAPSED_WIDTH = 84
const SHELL_GAP = 12
const COLLAPSE_DURATION = 0.28

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function animateCollapse(collapsed) {
  if (collapseTween) {
    collapseTween.kill()
    collapseTween = null
  }

  const sidebarEl = sidebarRef.value?.$el
  const mainEl = mainRef.value
  if (!sidebarEl || !mainEl) return

  const fromWidth = collapsed ? SIDEBAR_EXPANDED_WIDTH : SIDEBAR_COLLAPSED_WIDTH
  const toWidth = collapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_EXPANDED_WIDTH
  const fromMargin = collapsed
    ? SIDEBAR_EXPANDED_WIDTH + SHELL_GAP
    : SIDEBAR_COLLAPSED_WIDTH + SHELL_GAP
  const toMargin = collapsed
    ? SIDEBAR_COLLAPSED_WIDTH + SHELL_GAP
    : SIDEBAR_EXPANDED_WIDTH + SHELL_GAP

  sidebarEl.style.willChange = 'width'
  mainEl.style.willChange = 'margin-left'

  collapseTween = gsap.timeline({
    onComplete() {
      sidebarEl.style.willChange = ''
      mainEl.style.willChange = ''
      collapseTween = null
    }
  })

  collapseTween.to(sidebarEl, {
    width: toWidth,
    duration: COLLAPSE_DURATION,
    ease: 'power2.out'
  }, 0)

  collapseTween.to(mainEl, {
    marginLeft: toMargin,
    duration: COLLAPSE_DURATION,
    ease: 'power2.out'
  }, 0)
}

onMounted(() => {
  sidebarCollapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'true'

  const isLowPerf = navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (isLowPerf || prefersReduced) {
    document.documentElement.classList.add('reduced-animation')
  }
})

watch(sidebarCollapsed, (value) => {
  window.localStorage.setItem(SIDEBAR_STORAGE_KEY, String(value))
  nextTick(() => animateCollapse(value))
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
  /* 淡墨晕染：静态氛围层（宣纸墨韵 §4.1），替代原常驻呼吸动画 */
  background:
    radial-gradient(56rem 36rem at 10% -6%, rgba(120, 108, 84, 0.07), transparent 55%),
    radial-gradient(48rem 32rem at 96% 4%, rgba(166, 61, 42, 0.04), transparent 50%),
    radial-gradient(60rem 40rem at 50% 108%, rgba(120, 108, 84, 0.06), transparent 60%);
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
  contain: layout style;
}

.app-main.collapsed {
  /* margin-left 由 GSAP 控制，CSS 不再硬切 */
}

.app-shell.is-auth {
  padding: 0;
  gap: 0;
}

.app-main.is-auth {
  margin-left: 0;
  height: 100dvh;
  border-radius: 0;
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

@media (max-width: 900px) {
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
