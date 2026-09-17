import { ref } from 'vue'

const HIGHLIGHT_LANGUAGE_LOADERS = [
  ['bash', () => import('highlight.js/lib/languages/bash')],
  ['java', () => import('highlight.js/lib/languages/java')],
  ['javascript', () => import('highlight.js/lib/languages/javascript')],
  ['json', () => import('highlight.js/lib/languages/json')],
  ['kotlin', () => import('highlight.js/lib/languages/kotlin')],
  ['markdown', () => import('highlight.js/lib/languages/markdown')],
  ['plaintext', () => import('highlight.js/lib/languages/plaintext')],
  ['python', () => import('highlight.js/lib/languages/python')],
  ['sql', () => import('highlight.js/lib/languages/sql')],
  ['typescript', () => import('highlight.js/lib/languages/typescript')],
  ['xml', () => import('highlight.js/lib/languages/xml')],
  ['yaml', () => import('highlight.js/lib/languages/yaml')]
]

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

// 模型输出的标题常写成 "##1.先把心态放稳"（# 后无空格），
// 标准 Markdown 不识别，整篇会糊成一堵字墙。这里按行补齐空格做容错。
function normalizeModelMarkdown(content) {
  return String(content).replace(/^(#{1,6})(?=[^\s#])/gm, '$1 ')
}

export function useMarkdownRenderer() {
  const markdownRendererVersion = ref(0)
  const markdownCache = new Map()
  let markedInstance = null
  let highlightInstance = null
  let markdownInitPromise = null

  // Stream rendering throttle state
  let lastStreamRenderTime = 0
  let lastStreamRenderedContent = ''
  let lastStreamRenderedHtml = ''
  const STREAM_RENDER_THROTTLE_MS = 60
  const STREAM_RENDER_THROTTLE_THRESHOLD = 800

  async function initializeMarkdownRenderer() {
    if (markdownInitPromise) {
      return markdownInitPromise
    }

    markdownInitPromise = Promise.all([
      import('marked'),
      import('highlight.js/lib/core'),
      ...HIGHLIGHT_LANGUAGE_LOADERS.map(([, loader]) => loader()),
      import('highlight.js/styles/github.css')
    ])
      .then(([markedModule, hljsModule, ...loadedModules]) => {
        markedInstance = markedModule.marked
        highlightInstance = hljsModule.default
        HIGHLIGHT_LANGUAGE_LOADERS.forEach(([name], index) => {
          highlightInstance.registerLanguage(name, loadedModules[index].default)
        })
        markedInstance.setOptions({
          breaks: true,
          highlight(code, language) {
            if (language && highlightInstance?.getLanguage(language)) {
              return highlightInstance.highlight(code, { language }).value
            }
            return highlightInstance?.highlightAuto(code).value || escapeHtml(code)
          }
        })
        markdownCache.clear()
        markdownRendererVersion.value += 1
      })
      .catch((error) => {
        markdownInitPromise = null
        throw error
      })

    return markdownInitPromise
  }

  function renderMarkdownWithCache(content) {
    if (!content) {
      return ''
    }
    const normalized = normalizeModelMarkdown(content)
    const cacheKey = `${markdownRendererVersion.value}:${normalized}`
    if (markdownCache.has(cacheKey)) {
      return markdownCache.get(cacheKey)
    }
    const html = markedInstance
      ? markedInstance.parse(normalized)
      : escapeHtml(normalized).replace(/\n/g, '<br>')
    markdownCache.set(cacheKey, html)
    if (markdownCache.size > 80) {
      const oldestKey = markdownCache.keys().next().value
      markdownCache.delete(oldestKey)
    }
    return html
  }

  function renderMarkdown(content) {
    return renderMarkdownWithCache(content)
  }

  function renderStreamMarkdown(content) {
    if (!content) return ''

    const now = performance.now()
    const elapsed = now - lastStreamRenderTime
    const contentGrowth = content.length - lastStreamRenderedContent.length

    // Throttle: skip re-parse if rendered recently AND content growth is small
    // This prevents O(n²) re-parsing on every frame during streaming
    if (
      lastStreamRenderedHtml
      && elapsed < STREAM_RENDER_THROTTLE_MS
      && contentGrowth < STREAM_RENDER_THROTTLE_THRESHOLD
      && content.startsWith(lastStreamRenderedContent)
    ) {
      // Reuse previous HTML, only escape the new tail
      const tail = content.slice(lastStreamRenderedContent.length)
      const escapedTail = escapeHtml(tail).replace(/\n/g, '<br>')
      return lastStreamRenderedHtml + escapedTail + '<span class="stream-cursor"></span>'
    }

    const html = renderMarkdownWithCache(content)
    lastStreamRenderTime = now
    lastStreamRenderedContent = content
    lastStreamRenderedHtml = html
    return html + '<span class="stream-cursor"></span>'
  }

  function resetStreamCache() {
    lastStreamRenderTime = 0
    lastStreamRenderedContent = ''
    lastStreamRenderedHtml = ''
  }

  return {
    initializeMarkdownRenderer,
    renderMarkdown,
    renderStreamMarkdown,
    resetStreamCache
  }
}
