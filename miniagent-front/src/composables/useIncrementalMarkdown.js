import { ref } from 'vue'

/**
 * useIncrementalMarkdown - Incremental Markdown renderer that only parses new content.
 *
 * Features:
 * - Maintains a parsed offset pointer, only parses new text
 * - Detects unclosed Markdown syntax (code blocks), renders as plain text until closed
 * - Applies syntax highlighting once a code block is closed
 * - Defers rendering to next frame if it takes > 8ms
 */
export function useIncrementalMarkdown() {
  const renderedHtml = ref('')

  let fullText = ''
  let parsedOffset = 0
  let htmlSegments = []
  let pendingCodeBlock = null // { lang: string, content: string, startOffset: number }
  let markedInstance = null
  let highlightInstance = null
  let initPromise = null

  async function ensureInit() {
    if (markedInstance) return
    if (initPromise) {
      await initPromise
      return
    }
    initPromise = Promise.all([
      import('marked'),
      import('highlight.js/lib/core'),
      import('highlight.js/lib/languages/javascript'),
      import('highlight.js/lib/languages/typescript'),
      import('highlight.js/lib/languages/python'),
      import('highlight.js/lib/languages/java'),
      import('highlight.js/lib/languages/bash'),
      import('highlight.js/lib/languages/json'),
      import('highlight.js/lib/languages/sql'),
      import('highlight.js/lib/languages/xml'),
      import('highlight.js/lib/languages/yaml'),
      import('highlight.js/lib/languages/kotlin'),
      import('highlight.js/lib/languages/markdown'),
      import('highlight.js/lib/languages/plaintext')
    ]).then(([markedMod, hljsMod, ...langs]) => {
      markedInstance = markedMod.marked
      highlightInstance = hljsMod.default
      const langNames = ['javascript', 'typescript', 'python', 'java', 'bash', 'json', 'sql', 'xml', 'yaml', 'kotlin', 'markdown', 'plaintext']
      langNames.forEach((name, i) => {
        highlightInstance.registerLanguage(name, langs[i].default)
      })
      markedInstance.setOptions({
        breaks: true,
        highlight(code, language) {
          if (language && highlightInstance.getLanguage(language)) {
            return highlightInstance.highlight(code, { language }).value
          }
          return highlightInstance.highlightAuto(code).value || escapeHtml(code)
        }
      })
    })
    await initPromise
  }

  function append(text) {
    if (!text) return
    fullText += text
    incrementalParse()
  }

  function finalize() {
    // Force-close any pending code block and render remaining
    if (pendingCodeBlock) {
      // Render the unclosed code block as a code block anyway
      const codeHtml = renderCodeBlock(pendingCodeBlock.lang, pendingCodeBlock.content)
      htmlSegments.push(codeHtml)
      pendingCodeBlock = null
    }
    // Parse any remaining text after the last code block
    if (parsedOffset < fullText.length) {
      const remaining = fullText.substring(parsedOffset)
      const html = renderInlineMarkdown(remaining)
      htmlSegments.push(html)
      parsedOffset = fullText.length
    }
    renderedHtml.value = htmlSegments.join('')
  }

  function reset() {
    fullText = ''
    parsedOffset = 0
    htmlSegments = []
    pendingCodeBlock = null
    renderedHtml.value = ''
  }

  function incrementalParse() {
    const startTime = performance.now()

    while (parsedOffset < fullText.length) {
      // Check time budget (8ms)
      if (performance.now() - startTime > 8) {
        // Defer remaining to next frame
        requestAnimationFrame(() => incrementalParse())
        break
      }

      if (pendingCodeBlock) {
        // Look for closing ```
        const closeIndex = fullText.indexOf('\n```', parsedOffset)
        if (closeIndex === -1) {
          // Still unclosed - accumulate content but don't render yet
          pendingCodeBlock.content = fullText.substring(pendingCodeBlock.startOffset, fullText.length)
          parsedOffset = fullText.length
          // Show pending code block as plain text
          updateRenderedHtml()
          return
        } else {
          // Code block closed
          const endOfClose = fullText.indexOf('\n', closeIndex + 4)
          const actualEnd = endOfClose === -1 ? closeIndex + 3 : closeIndex + 3
          pendingCodeBlock.content = fullText.substring(pendingCodeBlock.startOffset, actualEnd)
          parsedOffset = actualEnd + 1 // skip past closing ```\n
          if (parsedOffset > fullText.length) parsedOffset = fullText.length

          // Render the complete code block with highlighting
          const codeHtml = renderCodeBlock(pendingCodeBlock.lang, pendingCodeBlock.content)
          htmlSegments.push(codeHtml)
          pendingCodeBlock = null
        }
      } else {
        // Look for opening ```
        const openIndex = fullText.indexOf('```', parsedOffset)
        if (openIndex === -1) {
          // No code block - render all remaining as inline markdown
          const segment = fullText.substring(parsedOffset)
          // Only render complete lines (wait for newline at end)
          const lastNewline = segment.lastIndexOf('\n')
          if (lastNewline === -1) {
            // No complete line yet, wait
            updateRenderedHtml()
            return
          }
          const completeText = segment.substring(0, lastNewline + 1)
          const html = renderInlineMarkdown(completeText)
          htmlSegments.push(html)
          parsedOffset += completeText.length
          updateRenderedHtml()
          return
        } else {
          // Render text before the code block
          if (openIndex > parsedOffset) {
            const beforeCode = fullText.substring(parsedOffset, openIndex)
            const html = renderInlineMarkdown(beforeCode)
            htmlSegments.push(html)
          }
          // Extract language hint
          const langEnd = fullText.indexOf('\n', openIndex + 3)
          if (langEnd === -1) {
            // Opening ``` but no newline yet - wait
            parsedOffset = openIndex
            updateRenderedHtml()
            return
          }
          const lang = fullText.substring(openIndex + 3, langEnd).trim()
          parsedOffset = langEnd + 1
          pendingCodeBlock = {
            lang,
            content: '',
            startOffset: parsedOffset
          }
        }
      }
    }

    updateRenderedHtml()
  }

  function updateRenderedHtml() {
    let html = htmlSegments.join('')
    // Append pending code block as plain text (escaped)
    if (pendingCodeBlock && pendingCodeBlock.content) {
      html += '<pre class="code-pending"><code>' + escapeHtml(pendingCodeBlock.content) + '</code></pre>'
    }
    // Append any trailing text that hasn't been parsed into a segment yet
    const trailingStart = pendingCodeBlock ? fullText.length : parsedOffset
    if (trailingStart < fullText.length) {
      const trailing = fullText.substring(trailingStart)
      html += escapeHtml(trailing).replace(/\n/g, '<br>')
    }
    renderedHtml.value = html
  }

  function renderInlineMarkdown(text) {
    if (!text) return ''
    if (markedInstance) {
      try {
        return markedInstance.parse(text)
      } catch {
        return escapeHtml(text).replace(/\n/g, '<br>')
      }
    }
    return escapeHtml(text).replace(/\n/g, '<br>')
  }

  function renderCodeBlock(lang, content) {
    if (!content) return ''
    let highlighted = escapeHtml(content)
    if (highlightInstance) {
      try {
        if (lang && highlightInstance.getLanguage(lang)) {
          highlighted = highlightInstance.highlight(content, { language: lang }).value
        } else {
          highlighted = highlightInstance.highlightAuto(content).value
        }
      } catch {
        // fallback to escaped
      }
    }
    const langClass = lang ? ` class="language-${lang}"` : ''
    return `<pre><code${langClass}>${highlighted}</code></pre>`
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
  }

  return {
    renderedHtml,
    append,
    finalize,
    reset,
    ensureInit
  }
}
