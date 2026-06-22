import { ref, onBeforeUnmount } from 'vue'

/**
 * useThinkingTransition - Manages smooth transition from thinking state to answer state.
 *
 * Features:
 * - Tracks phase: 'idle' | 'thinking' | 'transitioning' | 'answering'
 * - Shows dynamic ellipsis animation when thinking > 3s with no chunks
 * - Smooth fade-out (200ms) of thinking indicator, fade-in (150ms) of answer content
 * - Thinking content uses the same streaming pipeline (via external streaming engine)
 */
export function useThinkingTransition() {
  const phase = ref('idle') // 'idle' | 'thinking' | 'transitioning' | 'answering'
  const thinkingContent = ref('')
  const showEllipsis = ref(false)
  const isTransitioning = ref(false)

  let ellipsisTimer = null
  let lastThinkingChunkTime = 0
  let ellipsisCheckInterval = null

  function startThinking() {
    phase.value = 'thinking'
    thinkingContent.value = ''
    showEllipsis.value = false
    lastThinkingChunkTime = performance.now()
    startEllipsisCheck()
  }

  function appendThinkingChunk(chunk) {
    if (!chunk) return
    thinkingContent.value += chunk
    lastThinkingChunkTime = performance.now()
    showEllipsis.value = false
  }

  function transitionToAnswer() {
    if (phase.value === 'answering') return

    stopEllipsisCheck()
    phase.value = 'transitioning'
    isTransitioning.value = true

    // Fade out thinking (200ms), then fade in answer (150ms)
    setTimeout(() => {
      phase.value = 'answering'
      isTransitioning.value = false
    }, 200)
  }

  function reset() {
    phase.value = 'idle'
    thinkingContent.value = ''
    showEllipsis.value = false
    isTransitioning.value = false
    stopEllipsisCheck()
  }

  function startEllipsisCheck() {
    stopEllipsisCheck()
    ellipsisCheckInterval = setInterval(() => {
      if (phase.value !== 'thinking') {
        stopEllipsisCheck()
        return
      }
      const elapsed = performance.now() - lastThinkingChunkTime
      if (elapsed > 3000 && !thinkingContent.value) {
        showEllipsis.value = true
      }
    }, 500)
  }

  function stopEllipsisCheck() {
    if (ellipsisCheckInterval) {
      clearInterval(ellipsisCheckInterval)
      ellipsisCheckInterval = null
    }
    if (ellipsisTimer) {
      clearTimeout(ellipsisTimer)
      ellipsisTimer = null
    }
  }

  onBeforeUnmount(() => {
    stopEllipsisCheck()
  })

  return {
    phase,
    thinkingContent,
    showEllipsis,
    isTransitioning,
    startThinking,
    appendThinkingChunk,
    transitionToAnswer,
    reset
  }
}
