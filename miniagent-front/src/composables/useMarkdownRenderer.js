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

export function useMarkdownRenderer() {
  const markdownRendererVersion = ref(0)
  const markdownCache = new Map()
  let markedInstance = null
  let highlightInstance = null
  let markdownInitPromise = null

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
    const cacheKey = `${markdownRendererVersion.value}:${content}`
    if (markdownCache.has(cacheKey)) {
      return markdownCache.get(cacheKey)
    }
    const html = markedInstance
      ? markedInstance.parse(content)
      : escapeHtml(content).replace(/\n/g, '<br>')
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
    const html = renderMarkdownWithCache(content)
    return html + '<span class="stream-cursor"></span>'
  }

  return {
    initializeMarkdownRenderer,
    renderMarkdown,
    renderStreamMarkdown
  }
}
