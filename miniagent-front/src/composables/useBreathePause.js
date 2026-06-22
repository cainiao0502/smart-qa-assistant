import { onMounted, onUnmounted } from 'vue'

/**
 * useBreathePause - Pauses .jelly-breathe animations when elements leave the viewport.
 * Uses IntersectionObserver to detect visibility.
 * Falls back to always-playing if IntersectionObserver is not supported.
 */
export function useBreathePause(containerRef) {
  let observer = null

  function setupObserver() {
    if (!('IntersectionObserver' in window)) {
      // Fallback: do nothing, animations always play
      return
    }

    observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.target.classList.contains('jelly-breathe')) {
            entry.target.style.animationPlayState = entry.isIntersecting ? 'running' : 'paused'
          }
        })
      },
      { threshold: 0 }
    )

    // Observe all .jelly-breathe elements within the container
    const container = containerRef?.value || document
    const elements = container.querySelectorAll('.jelly-breathe')
    elements.forEach((el) => observer.observe(el))
  }

  function cleanup() {
    if (observer) {
      observer.disconnect()
      observer = null
    }
  }

  onMounted(() => {
    setupObserver()
  })

  onUnmounted(() => {
    cleanup()
  })

  return { cleanup, setupObserver }
}
