import { ref, onBeforeUnmount } from 'vue'

/**
 * useStreamingEngine - Core streaming render engine with chunk buffering and
 * character-by-character release scheduling.
 *
 * Features:
 * - Chunk buffer queue (incoming chunks are queued, not directly rendered)
 * - requestAnimationFrame-based render scheduler
 * - Normal speed: 1-3 chars per frame; fast mode (buffer > 100): 5-10 chars per frame
 * - Latency detection: if no chunk arrives for > 200ms, render immediately on next chunk
 * - Background tab: pause rendering, flush all on visibility restore
 */
export function useStreamingEngine(options = {}) {
  const {
    normalSpeed = 2,
    fastSpeed = 7,
    fastThreshold = 100,
    latencyThreshold = 200
  } = options

  const displayedContent = ref('')
  const bufferedCount = ref(0)
  const isStreaming = ref(false)

  let buffer = ''
  let rafId = 0
  let lastChunkTime = 0
  let running = false

  function appendChunk(chunk) {
    if (!chunk) return
    buffer += chunk
    bufferedCount.value = buffer.length
    lastChunkTime = performance.now()

    // If scheduler is not running, start it
    if (running && !rafId) {
      scheduleFrame()
    }
  }

  function start() {
    running = true
    isStreaming.value = true
    scheduleFrame()
    document.addEventListener('visibilitychange', onVisibilityChange)
  }

  function flush() {
    cancelFrame()
    if (buffer.length > 0) {
      displayedContent.value += buffer
      buffer = ''
      bufferedCount.value = 0
    }
    running = false
    isStreaming.value = false
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }

  function reset() {
    cancelFrame()
    buffer = ''
    bufferedCount.value = 0
    displayedContent.value = ''
    lastChunkTime = 0
    running = false
    isStreaming.value = false
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }

  function scheduleFrame() {
    if (rafId) return
    rafId = window.requestAnimationFrame(renderTick)
  }

  function cancelFrame() {
    if (rafId) {
      window.cancelAnimationFrame(rafId)
      rafId = 0
    }
  }

  function renderTick() {
    rafId = 0

    if (!running) return

    if (buffer.length === 0) {
      // Buffer empty, keep scheduling to stay responsive
      scheduleFrame()
      return
    }

    // Memory pressure: if buffer exceeds 5000 chars, force flush to prevent lag
    if (buffer.length > 5000) {
      displayedContent.value += buffer
      buffer = ''
      bufferedCount.value = 0
      scheduleFrame()
      return
    }

    // Determine release speed based on buffer size
    const isFast = buffer.length > fastThreshold
    const speed = isFast ? fastSpeed : normalSpeed
    // Add slight randomness for natural feel (±1 char)
    const jitter = Math.random() > 0.5 ? 1 : 0
    const releaseCount = Math.min(speed + jitter, buffer.length)

    // Release characters, ensuring we don't split surrogate pairs
    let end = releaseCount
    if (end < buffer.length && isSurrogatePair(buffer, end - 1)) {
      end = Math.min(end + 1, buffer.length)
    }

    const released = buffer.substring(0, end)
    buffer = buffer.substring(end)
    displayedContent.value += released
    bufferedCount.value = buffer.length

    // Continue scheduling if still running
    if (running) {
      scheduleFrame()
    }
  }

  function isSurrogatePair(str, index) {
    const code = str.charCodeAt(index)
    return code >= 0xD800 && code <= 0xDBFF
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible') {
      // Flush all buffered content immediately on tab restore
      if (buffer.length > 0) {
        displayedContent.value += buffer
        buffer = ''
        bufferedCount.value = 0
      }
      if (running) {
        scheduleFrame()
      }
    } else {
      // Pause rendering when tab is hidden
      cancelFrame()
    }
  }

  onBeforeUnmount(() => {
    cancelFrame()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    displayedContent,
    bufferedCount,
    isStreaming,
    appendChunk,
    start,
    flush,
    reset
  }
}
