import { ref, onBeforeUnmount } from 'vue'

/**
 * useSmoothScroller - Smooth auto-scroll controller for streaming content.
 *
 * Features:
 * - requestAnimationFrame + scrollTop interpolation for smooth scrolling
 * - Pauses auto-scroll when user manually scrolls up > 50px, shows "back to bottom" button
 * - Throttles scroll updates to max once per 100ms
 * - Smooth animation when clicking "back to bottom"
 */
export function useSmoothScroller() {
  const autoScrollEnabled = ref(true)
  const showScrollToBottom = ref(false)

  let container = null
  let scrollRafId = 0
  let lastScrollUpdate = 0
  let pendingUpdate = false
  let throttleTimer = 0
  let animating = false

  function bindContainer(el) {
    if (container) {
      container.removeEventListener('scroll', onUserScroll)
    }
    container = el
    if (container) {
      container.addEventListener('scroll', onUserScroll, { passive: true })
    }
  }

  function unbind() {
    if (container) {
      container.removeEventListener('scroll', onUserScroll)
      container = null
    }
    cancelAnimation()
    if (throttleTimer) {
      clearTimeout(throttleTimer)
      throttleTimer = 0
    }
  }

  function onUserScroll() {
    if (animating) return // Ignore scroll events during programmatic animation

    if (!container) return
    const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight
    if (distanceToBottom > 50) {
      autoScrollEnabled.value = false
      showScrollToBottom.value = true
    } else {
      autoScrollEnabled.value = true
      showScrollToBottom.value = false
    }
  }

  /**
   * Notify that new content has been rendered. Throttled to max once per 100ms.
   */
  function onContentUpdated() {
    if (!autoScrollEnabled.value || !container) return

    const now = performance.now()
    if (now - lastScrollUpdate < 100) {
      // Throttle: schedule for later if not already pending
      if (!pendingUpdate) {
        pendingUpdate = true
        throttleTimer = setTimeout(() => {
          pendingUpdate = false
          throttleTimer = 0
          doSmoothScroll()
        }, 100 - (now - lastScrollUpdate))
      }
      return
    }

    doSmoothScroll()
  }

  function doSmoothScroll() {
    if (!container || !autoScrollEnabled.value) return
    lastScrollUpdate = performance.now()

    const target = container.scrollHeight - container.clientHeight
    const current = container.scrollTop
    const distance = target - current

    if (distance <= 1) return

    // For small distances, just set directly
    if (distance < 20) {
      container.scrollTop = target
      return
    }

    // Smooth interpolation
    animateScroll(current, target, 150)
  }

  /**
   * Scroll to bottom with smooth animation (user-triggered).
   */
  function scrollToBottom() {
    if (!container) return
    const target = container.scrollHeight - container.clientHeight
    animateScroll(container.scrollTop, target, 300)
    autoScrollEnabled.value = true
    showScrollToBottom.value = false
  }

  function animateScroll(from, to, duration) {
    cancelAnimation()
    animating = true
    const startTime = performance.now()

    function step(now) {
      const elapsed = now - startTime
      const progress = Math.min(elapsed / duration, 1)
      // Spring-like ease-out with slight overshoot
      const eased = progress < 1 
        ? 1 - Math.pow(1 - progress, 3) * Math.cos(progress * Math.PI * 0.5)
        : 1
      if (container) {
        container.scrollTop = from + (to - from) * eased
      }
      if (progress < 1) {
        scrollRafId = requestAnimationFrame(step)
      } else {
        scrollRafId = 0
        animating = false
      }
    }

    scrollRafId = requestAnimationFrame(step)
  }

  function cancelAnimation() {
    if (scrollRafId) {
      cancelAnimationFrame(scrollRafId)
      scrollRafId = 0
    }
    animating = false
  }

  onBeforeUnmount(() => {
    unbind()
  })

  return {
    autoScrollEnabled,
    showScrollToBottom,
    onContentUpdated,
    scrollToBottom,
    bindContainer,
    unbind
  }
}
