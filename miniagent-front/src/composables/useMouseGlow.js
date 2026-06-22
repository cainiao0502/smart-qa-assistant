import { onMounted, onUnmounted, ref } from 'vue'

/**
 * useMouseGlow - Tracks mouse position relative to a container element
 * and outputs CSS custom properties for a radial gradient glow effect.
 *
 * Features:
 * - Outputs --glow-x and --glow-y CSS custom properties (in px) on the container
 * - Uses requestAnimationFrame for throttling (only updates once per frame)
 * - Auto-disables on touch devices (hover: none)
 * - Auto-disables when prefers-reduced-motion is active
 * - Auto-disables on low-performance devices (hardwareConcurrency < 4)
 * - Cleans up all event listeners on component unmount
 */
export function useMouseGlow(containerRef, options = {}) {
  const { glowRadius = 200, glowOpacity = 0.06 } = options

  const isHovering = ref(false)
  const enabled = ref(true)

  let rafId = null
  let pendingX = 0
  let pendingY = 0
  let needsUpdate = false

  function checkSupport() {
    // Disable on touch-only devices
    if (window.matchMedia('(hover: none)').matches) {
      enabled.value = false
      return
    }
    // Disable if reduced motion preferred
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      enabled.value = false
      return
    }
    // Disable on low-performance devices
    if (navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4) {
      enabled.value = false
      return
    }
    enabled.value = true
  }

  function updateGlow() {
    if (!needsUpdate || !containerRef.value) return
    needsUpdate = false
    containerRef.value.style.setProperty('--glow-x', `${pendingX}px`)
    containerRef.value.style.setProperty('--glow-y', `${pendingY}px`)
    containerRef.value.style.setProperty('--glow-opacity', String(glowOpacity))
  }

  function onMouseMove(e) {
    if (!enabled.value || !containerRef.value) return
    const rect = containerRef.value.getBoundingClientRect()
    pendingX = e.clientX - rect.left
    pendingY = e.clientY - rect.top
    needsUpdate = true
    if (!rafId) {
      rafId = requestAnimationFrame(() => {
        updateGlow()
        rafId = null
      })
    }
  }

  function onMouseEnter() {
    isHovering.value = true
  }

  function onMouseLeave() {
    isHovering.value = false
    if (containerRef.value) {
      containerRef.value.style.setProperty('--glow-opacity', '0')
    }
  }

  function bind() {
    const el = containerRef.value
    if (!el) return
    el.addEventListener('mousemove', onMouseMove, { passive: true })
    el.addEventListener('mouseenter', onMouseEnter, { passive: true })
    el.addEventListener('mouseleave', onMouseLeave, { passive: true })
  }

  function unbind() {
    const el = containerRef.value
    if (!el) return
    el.removeEventListener('mousemove', onMouseMove)
    el.removeEventListener('mouseenter', onMouseEnter)
    el.removeEventListener('mouseleave', onMouseLeave)
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
  }

  onMounted(() => {
    checkSupport()
    if (enabled.value) {
      bind()
    }
  })

  onUnmounted(() => {
    unbind()
  })

  return {
    isHovering,
    enabled
  }
}
