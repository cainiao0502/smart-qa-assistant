import DOMPurify from 'dompurify'

/**
 * Markdown 渲染输出的统一消毒入口。
 *
 * <p>marked 官方明确不做 XSS 消毒（https://marked.js.org/#usage —— "marked does
 * not sanitize the output HTML"）。模型输出的 HTML 会经 v-html 直接插入 DOM，
 * 而输出内容可能复述知识库文档原文或 MCP 工具返回的外部内容——恶意文档可以
 * 借此注入 <img onerror>、<a href="javascript:"> 等载荷，属于 OWASP LLM01 的
 * 下游利用面。所有 marked 输出必须经过本函数再进 v-html。</p>
 *
 * <p>默认配置保留 highlight.js 的 <code>span.code class</code>、表格、链接等
 * 正常 Markdown 产物，剥除 script/事件处理器/javascript: 协议。</p>
 */
export function sanitizeHtml(html) {
  if (!html) {
    return html ?? ''
  }
  return DOMPurify.sanitize(html, {
    // 链接保留 target/rel 由 DOMPurify 默认策略处理；允许的 class 交给默认规则
    // （highlight.js 的 language-* 与代码高亮 span 均在白名单内）
    USE_PROFILES: { html: true }
  })
}
