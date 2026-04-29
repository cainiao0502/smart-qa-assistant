<template>
  <section class="chat-page">
    <div class="chat-shell glass-panel">
      <header class="chat-toolbar">
        <div class="toolbar-left">
          <span class="soft-chip">
            <span class="chip-dot"></span>
            智能问答
          </span>
          <span class="toolbar-desc">基于知识库检索结果生成回答</span>
        </div>

        <div class="chat-header-actions">
          <el-select
            v-model="selectedKbId"
            class="kb-select"
            placeholder="请选择知识库"
          >
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>

          <el-button plain class="ghost-btn" @click="toggleRetrievalSettings">
            <el-icon><Operation /></el-icon>
            检索设置
          </el-button>

          <el-button plain class="ghost-btn" @click="resetConversation">
            <el-icon><Delete /></el-icon>
            新对话
          </el-button>
        </div>
      </header>

      <section v-if="showRetrievalSettings" class="retrieval-panel glass-panel">
        <div class="retrieval-grid">
          <div class="setting-field">
            <span class="setting-label">TopK</span>
            <el-input-number v-model="retrievalOptions.topK" :min="1" :max="20" />
          </div>

          <div class="setting-field">
            <span class="setting-label">相似度阈值</span>
            <div class="setting-inline">
              <el-slider v-model="retrievalOptions.scoreThreshold" :min="0" :max="1" :step="0.05" />
              <span class="setting-value">{{ retrievalOptions.scoreThreshold.toFixed(2) }}</span>
            </div>
          </div>

          <div class="setting-field">
            <span class="setting-label">文档类型过滤</span>
            <el-select
              v-model="retrievalOptions.fileTypes"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="不过滤文档类型"
            >
              <el-option
                v-for="type in fileTypeOptions"
                :key="type"
                :label="type.toUpperCase()"
                :value="type"
              />
            </el-select>
          </div>

          <div class="setting-field">
            <span class="setting-label">文档名称关键字</span>
            <el-input
              v-model="retrievalOptions.documentNameKeyword"
              placeholder="例如：guide、部署、FAQ"
              clearable
            />
          </div>

          <div class="setting-field setting-field-full">
            <span class="setting-label">指定文档范围</span>
            <el-select
              v-model="retrievalOptions.documentIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="默认检索当前知识库全部文档"
            >
              <el-option
                v-for="doc in availableDocuments"
                :key="doc.id"
                :label="doc.name"
                :value="doc.id"
              />
            </el-select>
          </div>
        </div>

        <div class="retrieval-actions">
          <span class="setting-tip">这些设置只影响当前会话后续提问，不会修改知识库数据。</span>
          <el-button text @click="resetRetrievalOptions">恢复默认</el-button>
        </div>
      </section>

      <main ref="messagesContainer" class="chat-main">
        <section v-if="!messages.length && !isLoading" class="chat-empty">
          <div class="empty-icon-wrap">
            <div class="empty-icon animate-float">
              <svg width="42" height="42" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="empty-glow"></div>
          </div>

          <h2>今天我能帮你完成什么？</h2>
          <p>输入你的问题后，我会结合知识库检索片段给出回答，并保留会话上下文。</p>

          <div class="quick-prompts">
            <button
              v-for="prompt in exampleQuestions"
              :key="prompt.title"
              type="button"
              class="prompt-chip"
              @click="useExample(prompt.text)"
            >
              {{ prompt.text }}
            </button>
          </div>
        </section>

        <section v-else class="message-list">
          <template v-for="(message, index) in messages" :key="`${message?.role || 'unknown'}-${index}-${message?.createdAt || ''}`">
            <article
              v-if="shouldRenderMessage(message)"
              class="message-row"
              :class="message.role"
            >
              <div v-if="message.role === 'assistant'" class="message-side">
                <div class="message-avatar assistant-avatar">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </div>
              </div>

              <div class="message-stack" :class="message.role">
                <div class="message-meta">
                  <span>{{ message.role === 'user' ? '你' : 'Ragent' }}</span>
                  <span v-if="message.createdAt">{{ formatDateTime(message.createdAt) }}</span>
                </div>

                <div
                  v-if="isStreamingMessage(message) && thinkingContent && !message.content"
                  class="thinking-indicator"
                >
                  <div class="thinking-icon">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" stroke-dasharray="4 3"/><path d="M12 6v6l4 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
                  </div>
                  <span>深度思考中</span>
                </div>

                <div v-if="message.content" class="message-bubble" :class="{ 'is-streaming': isStreamingMessage(message) }">
                  <div
                    v-if="isStreamingMessage(message)"
                    class="message-stream-md"
                    v-html="renderStreamMarkdown(message.content)"
                  ></div>
                  <div
                    v-else
                    v-html="renderMarkdown(message.content)"
                  ></div>
                </div>

                <div
                  v-if="message.role === 'assistant' && message.retrievalConfig"
                  class="retrieval-summary"
                >
                  <span v-if="message.retrievalConfig.queryRewritten" class="retrieval-summary-chip">
                    Query Rewrite
                  </span>
                  <span v-if="message.retrievalConfig.reranked" class="retrieval-summary-chip">
                    Rerank
                  </span>
                  <span class="retrieval-summary-text">
                    {{ message.retrievalConfig.effectiveQuery || message.retrievalConfig.originalQuery }}
                  </span>
                </div>

                <div
                  v-if="message.role === 'assistant' && hasToolCalls(message)"
                  class="tool-panel"
                >
                  <div class="tool-header">
                    <span>工具调用</span>
                    <small>{{ message.toolCalls.length }} 次执行</small>
                  </div>
                  <div class="tool-list">
                    <div v-for="(toolCall, toolIndex) in message.toolCalls" :key="toolIndex" class="tool-item">
                      <div class="tool-top">
                        <div class="tool-title-block">
                          <strong>{{ formatToolHeadline(toolCall) }}</strong>
                          <span class="tool-subtitle">{{ formatToolSubtitle(toolCall) }}</span>
                        </div>
                        <div class="tool-badges">
                          <span class="tool-badge" :class="getToolStatusClass(toolCall.status)">
                            {{ formatToolStatus(toolCall.status) }}
                          </span>
                          <span v-if="toolCall.durationMs" class="tool-badge">{{ toolCall.durationMs }}ms</span>
                        </div>
                      </div>
                      <span v-if="toolCall.summary" class="tool-summary">{{ toolCall.summary }}</span>
                      <pre v-if="toolCall.arguments && Object.keys(toolCall.arguments).length" class="tool-arguments">{{ formatToolArguments(toolCall.arguments) }}</pre>
                    </div>
                  </div>
                </div>

                <div
                  v-if="message.role === 'assistant' && hasReferenceSource(message)"
                  class="reference-panel"
                >
                  <div class="reference-header">
                    <span>参考来源</span>
                    <small>{{ message.references.length }} 条命中片段</small>
                  </div>
                  <div class="reference-list">
                    <div v-for="(reference, refIndex) in message.references" :key="refIndex" class="reference-item">
                      <div class="reference-top">
                        <strong>{{ reference.documentName || `文档 #${reference.docId}` }}</strong>
                        <div class="reference-badges">
                          <span v-if="reference.fileType" class="reference-badge">{{ reference.fileType.toUpperCase() }}</span>
                          <span v-if="reference.score !== undefined && reference.score !== null" class="reference-badge score">
                            Score {{ formatScore(reference.score) }}
                          </span>
                          <span v-if="reference.rerankScore !== undefined && reference.rerankScore !== null" class="reference-badge rerank">
                            Rerank {{ formatScore(reference.rerankScore) }}
                          </span>
                        </div>
                      </div>
                      <span v-if="reference.hitReason" class="reference-reason">{{ reference.hitReason }}</span>
                      <span class="reference-text">{{ reference.chunkText }}</span>
                    </div>
                  </div>
                </div>

                <div v-if="message.role === 'assistant' && message.content" class="message-actions">
                  <button type="button" class="action-btn" @click="copyMessage(message.content)">
                    <el-icon><CopyDocument /></el-icon>
                    复制
                  </button>
                  <button type="button" class="action-btn" @click="rateMessage(index, 'like')" :class="{ active: message.liked }">
                    <el-icon><Select /></el-icon>
                  </button>
                  <button type="button" class="action-btn" @click="rateMessage(index, 'dislike')" :class="{ active: message.disliked }">
                    <el-icon><CloseBold /></el-icon>
                  </button>
                </div>
              </div>

              <div v-if="message.role === 'user'" class="message-side">
                <div class="message-avatar user-avatar">
                  <el-icon><User /></el-icon>
                </div>
              </div>
            </article>
          </template>

          <article
            v-if="isLoading && !streamingMessageHasContent"
            class="message-row assistant"
          >
            <div class="message-side">
              <div class="message-avatar assistant-avatar">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </div>
            </div>

            <div class="message-stack assistant">
              <div class="message-meta">
                <span>Ragent</span>
                <span class="thinking-label">思考中</span>
              </div>
              <div class="message-bubble loading-bubble">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </article>

          <div ref="messagesEndRef" class="messages-end-anchor" aria-hidden="true"></div>
        </section>
      </main>

      <footer class="chat-input-wrap">
        <div class="chat-input-card glass-panel">
          <div class="chat-input-meta">
            <div class="chat-input-hints">
              <span class="hint-badge">RAG 检索增强</span>
              <span class="hint-badge">{{ currentKnowledgeBaseName || '等待选择知识库' }}</span>
              <span class="hint-badge">TopK {{ retrievalOptions.topK }}</span>
              <span class="hint-badge">阈值 {{ retrievalOptions.scoreThreshold.toFixed(2) }}</span>
            </div>
            <span class="chat-session-tag">
              {{ sessionId ? `会话 ${sessionId.slice(-6)}` : '新会话' }}
            </span>
          </div>

          <div class="composer-box">
            <el-input
              v-model="question"
              type="textarea"
              :rows="1"
              :autosize="{ minRows: 2, maxRows: 6 }"
              resize="none"
              placeholder="例如：总结这个知识库里与部署步骤、接口说明和常见异常相关的核心要点。"
              @keydown.enter.exact.prevent="sendMessage"
            />

            <el-button
              type="primary"
              class="send-btn send-btn-inside"
              :loading="isLoading"
              :disabled="!question.trim() || !selectedKbId"
              @click="sendMessage"
            >
              <el-icon v-if="!isLoading"><Promotion /></el-icon>
              {{ isLoading ? '生成中...' : '发送问题' }}
            </el-button>
          </div>
        </div>
      </footer>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { chatApi, docApi, kbApi } from '@/api'
import { removeRecentSession, upsertRecentSession } from '@/utils/chatSessions'
import {
  CloseBold,
  CopyDocument,
  Delete,
  Operation,
  Promotion,
  Select,
  User
} from '@element-plus/icons-vue'

marked.setOptions({
  breaks: true,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      return hljs.highlight(code, { language }).value
    }
    return hljs.highlightAuto(code).value
  }
})

const DEFAULT_RETRIEVAL_OPTIONS = () => ({
  topK: 4,
  scoreThreshold: 0.6,
  documentIds: [],
  fileTypes: [],
  documentNameKeyword: ''
})

const route = useRoute()
const router = useRouter()

const question = ref('')
const isLoading = ref(false)
const selectedKbId = ref(null)
const knowledgeBases = ref([])
const availableDocuments = ref([])
const messages = ref([])
const sessionId = ref('')
const messagesContainer = ref(null)
const messagesEndRef = ref(null)
const isSendingFirstMessage = ref(false)
const streamingMessage = ref(null)
const pendingStreamDelta = ref('')
const pendingFlushHandle = ref(0)
const showRetrievalSettings = ref(false)
const retrievalOptions = ref(DEFAULT_RETRIEVAL_OPTIONS())
const STREAM_CONNECT_TIMEOUT_MS = 30000
const STREAM_IDLE_TIMEOUT_MS = 180000

const fileTypeOptions = ['pdf', 'txt', 'md', 'doc', 'docx']

const exampleQuestions = [
  { title: '快速开始', text: '这个知识库能帮我解决哪些典型问题？' },
  { title: '文档梳理', text: '帮我总结和部署相关的关键文档内容。' },
  { title: '使用帮助', text: '如果我要上传新文档，完整流程应该怎么走？' }
]

const currentKnowledgeBaseName = computed(() => {
  return knowledgeBases.value.find((item) => item.id === selectedKbId.value)?.name || ''
})

const streamingMessageHasContent = computed(() => Boolean(streamingMessage.value?.content))

function generateSessionId() {
  return `sess_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function renderMarkdown(content) {
  return content ? marked.parse(content) : ''
}

function renderStreamMarkdown(content) {
  if (!content) return ''
  const html = marked.parse(content)
  return html + '<span class="stream-cursor"></span>'
}

function formatScore(score) {
  return Number(score).toFixed(3)
}

function normalizeReferences(message) {
  if (Array.isArray(message.references)) {
    return message.references
  }

  if (typeof message.referencesJson === 'string' && message.referencesJson !== 'null') {
    try {
      const parsed = JSON.parse(message.referencesJson)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return []
    }
  }

  return []
}

function normalizeToolCalls(message) {
  if (Array.isArray(message.toolCalls)) {
    return message.toolCalls
  }

  if (typeof message.toolCallsJson === 'string' && message.toolCallsJson !== 'null') {
    try {
      const parsed = JSON.parse(message.toolCallsJson)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return []
    }
  }

  return []
}

function normalizeMessage(message) {
  const references = normalizeReferences(message)
  const toolCalls = normalizeToolCalls(message)
  return {
    kbId: message.kbId ?? null,
    role: String(message.role || '').toLowerCase(),
    content: message.content || '',
    createdAt: message.createdAt || '',
    retrievalConfig: message.retrievalConfig || null,
    references,
    toolCalls,
    liked: false,
    disliked: false
  }
}

function hasReferenceSource(message) {
  return Array.isArray(message.references) && message.references.length > 0
}

function hasToolCalls(message) {
  return Array.isArray(message.toolCalls) && message.toolCalls.length > 0
}

function getToolStatusClass(status) {
  return {
    SUCCESS: 'success',
    RUNNING: 'warning',
    FAILED: 'danger'
  }[status] || 'info'
}

function formatToolStatus(status) {
  return {
    SUCCESS: '成功',
    RUNNING: '执行中',
    FAILED: '失败'
  }[status] || (status || '未知')
}

function formatToolArguments(argumentsObject) {
  return JSON.stringify(argumentsObject, null, 2)
}

function formatToolHeadline(toolCall) {
  const source = toolCall?.source || ''
  if (source.startsWith('mcp:')) {
    return `已调用 MCP · ${source.slice(4)}`
  }
  return `已调用工具 · ${toolCall?.displayName || toolCall?.toolName || 'unknown'}`
}

function formatToolSubtitle(toolCall) {
  const source = toolCall?.source || ''
  if (source.startsWith('mcp:')) {
    return toolCall?.displayName || toolCall?.toolName || source.slice(4)
  }
  return source || 'builtin'
}

function shouldRenderMessage(message) {
  if (!message || !message.role) {
    return false
  }
  if (message.role !== 'assistant') {
    return true
  }
  return Boolean(message.content || hasReferenceSource(message) || hasToolCalls(message) || message !== streamingMessage.value)
}

function isStreamingMessage(message) {
  return Boolean(streamingMessage.value && message === streamingMessage.value)
}

function flushStreamDelta() {
  if (!streamingMessage.value || !pendingStreamDelta.value) {
    pendingFlushHandle.value = 0
    return
  }
  streamingMessage.value.content += pendingStreamDelta.value
  pendingStreamDelta.value = ''
  pendingFlushHandle.value = 0
}

function appendStreamDelta(delta) {
  if (!delta) {
    return
  }
  pendingStreamDelta.value += delta
  if (pendingFlushHandle.value) {
    return
  }
  pendingFlushHandle.value = window.requestAnimationFrame(flushStreamDelta)
}

function extractSessionTitle(messageList) {
  const firstUserMessage = messageList.find((item) => item.role === 'user')
  return firstUserMessage?.content?.slice(0, 22) || '未命名会话'
}

function extractSessionPreview(messageList) {
  const lastMessage = messageList.at(-1)
  return lastMessage?.content?.slice(0, 40) || ''
}

function syncSessionSummary() {
  if (!sessionId.value || !messages.value.length) {
    return
  }

  upsertRecentSession({
    id: sessionId.value,
    title: extractSessionTitle(messages.value),
    preview: extractSessionPreview(messages.value),
    kbId: selectedKbId.value,
    kbName: currentKnowledgeBaseName.value,
    updatedAt: new Date().toISOString()
  })
}

async function fetchKnowledgeBases() {
  const response = await kbApi.list()
  knowledgeBases.value = response.data || []
  if (!selectedKbId.value && knowledgeBases.value.length) {
    selectedKbId.value = knowledgeBases.value[0].id
  }
}

async function fetchDocuments(kbId) {
  if (!kbId) {
    availableDocuments.value = []
    retrievalOptions.value.documentIds = []
    return
  }

  const response = await docApi.list(kbId)
  const documents = response.data || []
  availableDocuments.value = documents

  const validDocIds = new Set(documents.map((item) => item.id))
  retrievalOptions.value.documentIds = retrievalOptions.value.documentIds.filter((id) => validDocIds.has(id))
}

async function loadSessionMessages(targetSessionId) {
  if (!targetSessionId) {
    messages.value = []
    sessionId.value = ''
    return
  }

  const response = await chatApi.getMessages(targetSessionId)
  const historyMessages = (response.data || []).map(normalizeMessage)
  messages.value = historyMessages
  sessionId.value = targetSessionId

  const matchedKbId = response.data?.[0]?.kbId
  if (matchedKbId) {
    selectedKbId.value = matchedKbId
  }

  syncSessionSummary()
  await scrollToBottom()
}

async function initializeSessionFromRoute() {
  const routeSessionId = typeof route.query.session === 'string' ? route.query.session : ''

  if (routeSessionId) {
    try {
      await loadSessionMessages(routeSessionId)
      return
    } catch (error) {
      ElMessage.error(error.message || '恢复历史会话失败')
      removeRecentSession(routeSessionId)
    }
  }

  sessionId.value = ''
  messages.value = []
}

const thinkingContent = ref('')

function createAssistantMessage() {
  return {
    kbId: selectedKbId.value,
    role: 'assistant',
    content: '',
    retrievalConfig: null,
    references: [],
    toolCalls: [],
    createdAt: new Date().toISOString(),
    liked: false,
    disliked: false
  }
}

function buildChatPayload(content) {
  const documentIds = retrievalOptions.value.documentIds.length ? retrievalOptions.value.documentIds : null
  const fileTypes = retrievalOptions.value.fileTypes.length ? retrievalOptions.value.fileTypes : null

  return {
    kbId: selectedKbId.value,
    sessionId: sessionId.value,
    question: content,
    topK: retrievalOptions.value.topK,
    scoreThreshold: Number(retrievalOptions.value.scoreThreshold.toFixed(2)),
    documentIds,
    fileTypes,
    documentNameKeyword: retrievalOptions.value.documentNameKeyword.trim() || null
  }
}

function parseSsePayload(raw) {
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

async function extractResponseError(response) {
  try {
    const buffer = await response.arrayBuffer()
    const text = new TextDecoder('utf-8').decode(buffer).trim()
    if (!text) {
      return `请求失败（HTTP ${response.status}）`
    }

    try {
      const payload = JSON.parse(text)
      if (typeof payload?.message === 'string' && payload.message.trim()) {
        return payload.message.trim()
      }
    } catch {
      // Ignore JSON parse errors and fall back to plain text.
    }

    return text
  } catch {
    return `请求失败（HTTP ${response.status}）`
  }
}

async function consumeStreamResponse(response, handlers) {
  if (!response.body) {
    throw new Error('stream response body is empty')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines = []

  const dispatchEvent = async () => {
    if (!dataLines.length) {
      eventName = 'message'
      return
    }

    const payload = parseSsePayload(dataLines.join('\n'))
    dataLines = []

    switch (eventName) {
      case 'start':
      case 'meta':
      case 'ping':
        handlers.onMeta?.(payload)
        break
      case 'message':
        handlers.onMessage?.(payload)
        break
      case 'thinking':
      case 'think':
        handlers.onThinking?.(payload)
        break
      case 'references':
        handlers.onReferences?.(payload)
        break
      case 'tool_result':
        handlers.onToolResult?.(payload)
        break
      case 'tool_calls':
        handlers.onToolCalls?.(payload)
        break
      case 'finish':
        handlers.onFinish?.(payload)
        break
      case 'done':
        handlers.onDone?.(payload)
        break
      case 'error':
        handlers.onError?.(payload)
        break
      default:
        break
    }

    eventName = 'message'
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      await dispatchEvent()
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''

    for (const line of lines) {
      if (!line) {
        await dispatchEvent()
        continue
      }

      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        continue
      }

      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
  }
}

async function sendMessage() {
  if (!question.value.trim() || !selectedKbId.value || isLoading.value) {
    return
  }

  const isNewConversation = !sessionId.value
  if (isNewConversation) {
    isSendingFirstMessage.value = true
    sessionId.value = generateSessionId()
    await router.replace({ path: '/', query: { session: sessionId.value } })
  }

  const content = question.value.trim()
  const payload = buildChatPayload(content)
  question.value = ''
  messages.value.push({
    kbId: selectedKbId.value,
    role: 'user',
    content,
    createdAt: new Date().toISOString()
  })
  syncSessionSummary()
  await scrollToBottom()

  messages.value.push(createAssistantMessage())
  const assistantMessage = messages.value[messages.value.length - 1]
  streamingMessage.value = assistantMessage
  isLoading.value = true
  const abortController = new AbortController()
  let streamTimer = 0
  let hasReceivedStreamEvent = false

  const resetStreamTimer = (timeoutMs = STREAM_IDLE_TIMEOUT_MS) => {
    if (streamTimer) {
      window.clearTimeout(streamTimer)
    }
    streamTimer = window.setTimeout(() => {
      abortController.abort(new DOMException('stream-timeout', 'AbortError'))
    }, timeoutMs)
  }

  try {
    resetStreamTimer(STREAM_CONNECT_TIMEOUT_MS)
    const streamResponse = await chatApi.stream(payload, {
      signal: abortController.signal
    })

    if (!streamResponse.ok) {
      throw new Error(await extractResponseError(streamResponse))
    }

    await consumeStreamResponse(streamResponse, {
      onMeta(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        if (streamPayload?.sessionId && !sessionId.value) {
          sessionId.value = streamPayload.sessionId
        }
      },
      onThinking(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        const delta = typeof streamPayload === 'string'
          ? streamPayload
          : (streamPayload?.delta || '')
        thinkingContent.value += delta
      },
      onMessage(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        const delta = typeof streamPayload === 'string'
          ? streamPayload
          : (streamPayload?.delta || '')
        appendStreamDelta(delta)
      },
      onReferences(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        assistantMessage.references = Array.isArray(streamPayload) ? streamPayload : []
      },
      onToolResult(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        if (streamPayload && typeof streamPayload === 'object') {
          assistantMessage.toolCalls = [...assistantMessage.toolCalls, streamPayload]
        }
      },
      onToolCalls(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        assistantMessage.toolCalls = Array.isArray(streamPayload) ? streamPayload : []
      },
      onFinish() {
        hasReceivedStreamEvent = true
        resetStreamTimer()
      },
      onDone(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        if (typeof streamPayload === 'string') {
          return
        }
        if (!assistantMessage.content && streamPayload?.answer) {
          assistantMessage.content = streamPayload.answer
        }
        if (streamPayload?.retrievalConfig) {
          assistantMessage.retrievalConfig = streamPayload.retrievalConfig
        }
        if (Array.isArray(streamPayload?.references)) {
          assistantMessage.references = streamPayload.references
        }
        if (Array.isArray(streamPayload?.toolCalls)) {
          assistantMessage.toolCalls = streamPayload.toolCalls
        }
      },
      onError(streamPayload) {
        throw new Error(streamPayload?.message || 'stream generation failed')
      }
    })

    flushStreamDelta()

    if (!assistantMessage.content.trim()) {
      throw new Error('模型暂时没有返回有效回答，请稍后重试')
    }

    syncSessionSummary()
  } catch (error) {
    const isAbortError = error?.name === 'AbortError' || error?.message === 'stream-timeout'
    const fallbackMessage = hasReceivedStreamEvent
      ? '回答生成中断了，已保留当前已返回的内容。你可以继续追问，或重新发送一次。'
      : '回答等待超时了，当前没有收到模型返回内容。请稍后重试，或检查后端日志。'
    assistantMessage.content = assistantMessage.content || fallbackMessage
    ElMessage.error(isAbortError ? '流式回答超时，已停止等待' : (error.message || '获取回答失败'))
  } finally {
    thinkingContent.value = ''
    if (streamTimer) {
      window.clearTimeout(streamTimer)
    }
    if (pendingFlushHandle.value) {
      window.cancelAnimationFrame(pendingFlushHandle.value)
      flushStreamDelta()
    }
    isLoading.value = false
    isSendingFirstMessage.value = false
    streamingMessage.value = null
    await scrollToBottom()
  }
}

function toggleRetrievalSettings() {
  showRetrievalSettings.value = !showRetrievalSettings.value
}

function resetRetrievalOptions() {
  retrievalOptions.value = DEFAULT_RETRIEVAL_OPTIONS()
}

function useExample(text) {
  question.value = text
}

async function resetConversation() {
  messages.value = []
  question.value = ''
  sessionId.value = ''
  await router.replace({ path: '/' })
}

async function scrollToBottom() {
  await nextTick()
  requestAnimationFrame(() => {
    if (messagesEndRef.value?.scrollIntoView) {
      messagesEndRef.value.scrollIntoView({ block: 'end' })
    }

    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

async function copyMessage(content) {
  await navigator.clipboard.writeText(content)
  ElMessage.success('已复制到剪贴板')
}

function rateMessage(index, type) {
  const target = messages.value[index]
  if (!target) {
    return
  }

  if (type === 'like') {
    target.liked = !target.liked
    target.disliked = false
  } else {
    target.disliked = !target.disliked
    target.liked = false
  }
}

function formatDateTime(value) {
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

watch(
  () => route.query.session,
  async (value, oldValue) => {
    if (value === oldValue) return
    if (isSendingFirstMessage.value && value === sessionId.value) return
    if (value === sessionId.value && (messages.value.length || isLoading.value)) return
    await initializeSessionFromRoute()
  }
)

watch(
  () => selectedKbId.value,
  async (kbId, oldKbId) => {
    if (kbId === oldKbId) {
      return
    }

    try {
      await fetchDocuments(kbId)
    } catch (error) {
      availableDocuments.value = []
      retrievalOptions.value.documentIds = []
      ElMessage.error(error.message || '加载文档列表失败')
    }

    if (messages.value.length) {
      syncSessionSummary()
    }
  },
  { immediate: true }
)

watch(
  () => [messages.value.length, isLoading.value],
  async () => {
    await scrollToBottom()
  },
  { flush: 'post' }
)

onMounted(async () => {
  try {
    await fetchKnowledgeBases()
    await initializeSessionFromRoute()
  } catch (error) {
    ElMessage.error(error.message || '初始化聊天页面失败')
  }
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 14% 18%, rgba(123, 211, 255, 0.28), transparent 24%),
    radial-gradient(circle at 86% 12%, rgba(163, 230, 255, 0.24), transparent 22%),
    radial-gradient(circle at 72% 82%, rgba(191, 240, 255, 0.18), transparent 24%),
    linear-gradient(180deg, #f8fbff 0%, #f4f8fc 46%, #eef4fb 100%);
}

.chat-shell {
  height: 100%;
  min-height: 0;
  padding: 16px;
  border-radius: var(--radius-xl);
  position: relative;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 14px;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(248, 251, 255, 0.94)),
    linear-gradient(135deg, rgba(109, 197, 255, 0.08), transparent 35%);
  border: 1px solid rgba(162, 191, 221, 0.28);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 56px rgba(114, 142, 176, 0.16),
    0 0 0 1px rgba(234, 241, 248, 0.72);
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(246, 250, 255, 0.92));
  border: 1px solid rgba(191, 210, 230, 0.42);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.88),
    0 10px 24px rgba(164, 182, 207, 0.12);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-desc {
  color: #5f6f85;
  font-size: 13px;
}

.chip-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent-color);
  box-shadow: 0 0 12px rgba(30, 200, 165, 0.56);
}

.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.kb-select {
  width: 240px;
}

.ghost-btn {
  min-width: 110px;
}

.chat-header-actions :deep(.el-select .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.96) !important;
  box-shadow: 0 0 0 1px rgba(148, 163, 184, 0.22) inset !important;
}

.chat-header-actions :deep(.el-select__placeholder),
.chat-header-actions :deep(.el-select__selected-item),
.chat-header-actions :deep(.el-input__inner),
.chat-header-actions :deep(.el-select__caret),
.chat-header-actions :deep(.el-input__icon) {
  color: #0f172a !important;
}

.chat-header-actions :deep(.el-button) {
  background: rgba(255, 255, 255, 0.96) !important;
  border-color: rgba(148, 163, 184, 0.24) !important;
  color: #0f172a !important;
}

.chat-header-actions :deep(.el-button span),
.chat-header-actions :deep(.el-button .el-icon) {
  color: #0f172a !important;
}

.retrieval-panel {
  padding: 16px;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.84), rgba(246, 250, 255, 0.9));
  border: 1px solid rgba(193, 211, 229, 0.4);
}

.retrieval-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
}

.setting-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.setting-field-full {
  grid-column: 1 / -1;
}

.setting-label {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.setting-inline {
  display: flex;
  align-items: center;
  gap: 12px;
}

.setting-inline :deep(.el-slider) {
  flex: 1;
}

.setting-value {
  min-width: 38px;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
}

.retrieval-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.setting-tip {
  color: var(--text-muted);
  font-size: 12px;
}

.chat-main {
  min-height: 0;
  overflow: auto;
  padding: 4px 8px 220px;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  background:
    linear-gradient(180deg, rgba(250, 252, 255, 0.48), rgba(240, 246, 252, 0.72)),
    radial-gradient(circle at top, rgba(119, 206, 255, 0.08), transparent 38%);
  border-radius: 28px;
}

.chat-empty {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 16px 0 24px;
}

.empty-icon-wrap {
  position: relative;
  margin-bottom: 18px;
}

.empty-icon {
  position: relative;
  z-index: 1;
  width: auto;
  height: auto;
  display: grid;
  place-items: center;
  color: #76a6d6;
  filter:
    drop-shadow(0 0 10px rgba(255, 255, 255, 0.42))
    drop-shadow(0 0 22px rgba(115, 130, 255, 0.34))
    drop-shadow(0 0 42px rgba(115, 130, 255, 0.26));
  animation: iconAura 3.4s ease-in-out infinite;
}

.empty-glow {
  position: absolute;
  inset: 50% auto auto 50%;
  width: 180px;
  height: 180px;
  transform: translate(-50%, -50%);
  background:
    radial-gradient(circle, rgba(132, 146, 255, 0.36), transparent 28%),
    radial-gradient(circle, rgba(115, 130, 255, 0.18), transparent 48%),
    radial-gradient(circle, rgba(115, 130, 255, 0.08), transparent 68%);
  pointer-events: none;
  animation: glowBreath 3.2s ease-in-out infinite;
  filter: blur(10px);
}

.chat-empty h2 {
  font-size: 34px;
  line-height: 1.08;
  margin-bottom: 10px;
  color: #24364d;
}

.chat-empty p {
  max-width: 620px;
  margin: 0 auto;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  margin-top: 18px;
}

.prompt-chip {
  border: 1px solid rgba(198, 214, 232, 0.6);
  background: rgba(255, 255, 255, 0.9);
  color: #62778f;
  border-radius: 999px;
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.prompt-chip:hover {
  color: #24364d;
  border-color: rgba(91, 108, 255, 0.22);
  background: rgba(240, 246, 255, 0.98);
}

.message-list {
  width: min(980px, 100%);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-bottom: 16px;
}

.messages-end-anchor {
  width: 100%;
  height: 1px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-side {
  width: 42px;
  flex-shrink: 0;
}

.message-avatar {
  width: 42px;
  height: 42px;
  border-radius: 16px;
  display: grid;
  place-items: center;
}

.assistant-avatar {
  background: linear-gradient(135deg, rgba(123, 211, 255, 0.96), rgba(92, 132, 255, 0.86));
  color: #ffffff;
  box-shadow:
    0 12px 24px rgba(101, 141, 192, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.user-avatar {
  background: linear-gradient(135deg, rgba(210, 239, 255, 0.92), rgba(181, 219, 255, 0.96));
  color: #4f86c6;
  box-shadow:
    0 10px 20px rgba(151, 177, 210, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.52);
}

.message-stack {
  max-width: min(720px, calc(100% - 54px));
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-stack.user {
  align-items: flex-end;
  max-width: min(560px, calc(100% - 54px));
}

.message-meta {
  display: flex;
  gap: 10px;
  color: #7a8ca4;
  font-size: 12px;
  font-weight: 700;
  padding-inline: 4px;
}

.message-bubble {
  position: relative;
  padding: 18px 22px;
  border-radius: 28px;
  line-height: 1.82;
  font-size: 14.5px;
  letter-spacing: 0;
  border: 1px solid rgba(199, 214, 231, 0.58);
  box-shadow:
    0 20px 44px rgba(159, 181, 208, 0.12),
    0 4px 12px rgba(159, 181, 208, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  transition:
    box-shadow var(--transition-fast),
    transform var(--transition-fast),
    border-color var(--transition-fast);
  overflow: hidden;
  backdrop-filter: blur(16px) saturate(130%);
  -webkit-backdrop-filter: blur(16px) saturate(130%);
}

.message-row:hover .message-bubble {
  transform: translateY(-1px);
}

.message-bubble.is-streaming {
  box-shadow:
    0 22px 48px rgba(159, 181, 208, 0.16),
    0 0 28px rgba(118, 196, 255, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.84);
}

.message-bubble::before {
  content: '';
  position: absolute;
  inset: 0;
  padding: 1px;
  border-radius: inherit;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(255, 255, 255, 0.18) 28%, rgba(178, 211, 245, 0.16) 72%, rgba(255, 255, 255, 0.5) 100%);
  -webkit-mask:
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
  opacity: 0.92;
}

.message-bubble::after {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: calc(28px - 1px);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.34), rgba(255, 255, 255, 0.08) 24%, transparent 44%),
    radial-gradient(circle at top left, rgba(255, 255, 255, 0.22), transparent 36%);
  pointer-events: none;
}

.message-stream-md {
  word-break: break-word;
}

.message-stream-md :deep(p) {
  margin: 0;
}

.stream-cursor {
  display: inline-block;
  width: 2.5px;
  height: 1.1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--primary-color);
  border-radius: 2px;
  animation: cursorBlink 0.88s step-end infinite;
}

.thinking-indicator {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 16px;
  background: rgba(219, 242, 255, 0.82);
  border: 1px solid rgba(162, 203, 230, 0.4);
  color: #4f88c7;
  font-size: 13px;
  font-weight: 600;
  animation: thinkingPulse 2s ease-in-out infinite;
}

.thinking-icon {
  display: grid;
  place-items: center;
  animation: thinkingSpin 3s linear infinite;
}

.thinking-label {
  color: var(--primary-color);
  font-weight: 700;
  animation: thinkingPulse 2s ease-in-out infinite;
}

.message-stack.assistant .message-bubble {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 251, 255, 0.98)),
    linear-gradient(135deg, rgba(140, 211, 255, 0.06), transparent 42%);
  color: #24364d;
  border-color: rgba(203, 217, 234, 0.72);
  border-top-left-radius: 14px;
  font-family: 'Inter', 'Plus Jakarta Sans', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.message-stack.user .message-bubble {
  background:
    linear-gradient(135deg, rgba(224, 240, 255, 0.98), rgba(208, 232, 255, 0.98) 56%, rgba(196, 226, 255, 0.96)),
    linear-gradient(180deg, rgba(255, 255, 255, 0.42), transparent 34%);
  color: #214c79;
  border-color: rgba(171, 203, 234, 0.72);
  border-top-right-radius: 14px;
  font-family: 'Plus Jakarta Sans', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-weight: 600;
  box-shadow:
    0 18px 36px rgba(150, 184, 220, 0.18),
    0 4px 10px rgba(150, 184, 220, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.message-stack.user .message-bubble::before {
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.78), rgba(255, 255, 255, 0.14) 34%, rgba(120, 182, 241, 0.24) 100%);
}

.message-stack.user .message-bubble::after {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.26), rgba(255, 255, 255, 0.06) 26%, transparent 42%),
    radial-gradient(circle at top left, rgba(255, 255, 255, 0.16), transparent 36%);
}

.message-bubble :deep(pre) {
  overflow: auto;
  padding: 16px 18px;
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(13, 18, 33, 0.98), rgba(10, 14, 25, 0.98)),
    linear-gradient(135deg, rgba(101, 141, 192, 0.16), transparent 40%);
  border: 1px solid rgba(144, 167, 200, 0.16);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.04),
    0 12px 28px rgba(20, 28, 48, 0.18);
  margin: 12px 0;
}

.message-bubble :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  font-variant-ligatures: common-ligatures;
}

.message-bubble :deep(:not(pre) > code) {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  background: rgba(231, 239, 248, 0.92);
  border: 2px solid rgba(150, 176, 208, 0.95);
  color: #31465f;
  font-size: 12px;
  line-height: 1.5;
  vertical-align: baseline;
}

.message-bubble :deep(p) {
  margin: 0 0 12px;
  line-height: 1.9;
}

.message-bubble :deep(p:last-child) {
  margin-bottom: 0;
}

.message-bubble :deep(ul),
.message-bubble :deep(ol) {
  padding-left: 24px;
  margin: 10px 0 14px;
}

.message-bubble :deep(li) {
  margin: 6px 0;
  line-height: 1.8;
}

.message-bubble :deep(blockquote) {
  margin: 12px 0;
  padding: 12px 16px;
  border-left: 5px solid #5f95d4;
  background: linear-gradient(180deg, rgba(241, 246, 252, 0.96), rgba(247, 250, 255, 0.98));
  border-top: 2px solid rgba(176, 200, 228, 0.9);
  border-right: 2px solid rgba(176, 200, 228, 0.9);
  border-bottom: 2px solid rgba(176, 200, 228, 0.9);
  border-radius: 0 16px 16px 0;
  color: #50657e;
}

.message-bubble :deep(h1),
.message-bubble :deep(h2),
.message-bubble :deep(h3),
.message-bubble :deep(h4) {
  margin: 18px 0 10px;
  font-weight: 800;
  line-height: 1.4;
  color: #1f334a;
}

.message-bubble :deep(h1) {
  font-size: 22px;
}

.message-bubble :deep(h2) {
  font-size: 19px;
}

.message-bubble :deep(h3) {
  font-size: 16px;
}

.message-bubble :deep(hr) {
  border: 0;
  height: 2px;
  margin: 16px 0;
  background: linear-gradient(90deg, rgba(148, 172, 201, 0), rgba(126, 156, 192, 0.96), rgba(148, 172, 201, 0));
}

.message-bubble :deep(a) {
  color: #2d6fbd;
  text-decoration: none;
  border-bottom: 1px solid rgba(45, 111, 189, 0.25);
  padding-bottom: 1px;
  transition: color var(--transition-fast), border-color var(--transition-fast), background var(--transition-fast);
}

.message-bubble :deep(a:hover) {
  color: #1d5ea8;
  border-color: rgba(29, 94, 168, 0.46);
}

.message-bubble :deep(a[href^="/"]) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 10px;
  background: rgba(236, 243, 250, 0.92);
  border: 2px solid rgba(150, 176, 208, 0.95);
  color: #34577d;
  font-weight: 700;
}

.message-bubble :deep(a[href^="/"]:hover) {
  background: rgba(229, 239, 250, 0.98);
}

.message-bubble :deep(table) {
  border-collapse: separate;
  border-spacing: 0;
  width: 100%;
  margin: 14px 0;
  overflow: hidden;
  border-radius: 16px;
  border: 2px solid rgba(158, 182, 210, 0.98);
  background: rgba(250, 252, 255, 0.98);
  box-shadow: 0 8px 22px rgba(173, 189, 211, 0.08);
}

.message-bubble :deep(th),
.message-bubble :deep(td) {
  padding: 12px 14px;
  border-right: 2px solid rgba(196, 213, 232, 0.95);
  border-bottom: 2px solid rgba(196, 213, 232, 0.95);
  text-align: left;
  vertical-align: top;
  line-height: 1.7;
}

.message-bubble :deep(th) {
  background: linear-gradient(180deg, rgba(244, 248, 252, 0.98), rgba(238, 243, 249, 0.98));
  font-weight: 800;
  color: #2d425a;
  border-bottom: 2px solid rgba(158, 182, 210, 0.98);
}

.message-bubble :deep(tr:last-child td) {
  border-bottom: 0;
}

.message-bubble :deep(th:last-child),
.message-bubble :deep(td:last-child) {
  border-right: 0;
}

.reference-panel {
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.86), rgba(244, 249, 254, 0.94));
  border: 1px solid rgba(194, 211, 228, 0.4);
  overflow: hidden;
}

.retrieval-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.retrieval-summary-chip {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(91, 108, 255, 0.12);
  color: #dbe6ff;
  font-size: 10px;
  font-weight: 700;
}

.retrieval-summary-text {
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-word;
}

.reference-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(91, 108, 255, 0.1);
  font-size: 12px;
  font-weight: 800;
}

.reference-header small {
  color: var(--text-muted);
  font-size: 11px;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
}

.reference-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.reference-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.reference-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.reference-badge {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-secondary);
  font-size: 10px;
  font-weight: 700;
}

.reference-badge.score {
  background: rgba(91, 108, 255, 0.16);
  color: #dbe6ff;
}

.reference-badge.rerank {
  background: rgba(34, 197, 94, 0.18);
  color: #dcfce7;
}

.reference-reason {
  color: #b8c3ff;
  font-size: 12px;
  line-height: 1.5;
}

.reference-text {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tool-panel {
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.86), rgba(244, 249, 254, 0.94));
  border: 1px solid rgba(194, 211, 228, 0.4);
  overflow: hidden;
}

.tool-header,
.tool-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.tool-header {
  padding: 12px 14px;
  border-bottom: 1px solid rgba(30, 200, 165, 0.1);
  font-size: 12px;
  font-weight: 800;
}

.tool-header small {
  color: var(--text-muted);
  font-size: 11px;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
}

.tool-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.tool-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tool-badge {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-secondary);
  font-size: 10px;
  font-weight: 700;
}

.tool-badge.success {
  background: rgba(34, 197, 94, 0.18);
  color: #dcfce7;
}

.tool-badge.warning {
  background: rgba(245, 158, 11, 0.18);
  color: #fde68a;
}

.tool-badge.danger {
  background: rgba(239, 68, 68, 0.18);
  color: #fecaca;
}

.tool-summary {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.tool-title-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-title-block strong {
  color: #24364d;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.tool-subtitle {
  color: #7a8ca4;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.tool-arguments {
  margin: 0;
  padding: 11px 12px;
  border-radius: 12px;
  background: rgba(248, 251, 255, 0.98);
  border: 1px solid rgba(209, 221, 235, 0.7);
  color: #324a62;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
}

.message-actions {
  display: flex;
  gap: 8px;
}

.action-btn {
  border: 0;
  border-radius: 999px;
  padding: 8px 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  background: rgba(244, 248, 253, 0.96);
  color: #6a7d95;
  font-size: 12px;
  font-weight: 700;
}

.action-btn.active {
  color: var(--primary-strong);
  background: rgba(91, 108, 255, 0.12);
}

.loading-bubble {
  display: inline-flex;
  gap: 8px;
}

.loading-bubble span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(91, 108, 255, 0.7);
  animation: pulse 1.1s infinite ease-in-out;
}

.loading-bubble span:nth-child(2) {
  animation-delay: 0.15s;
}

.loading-bubble span:nth-child(3) {
  animation-delay: 0.3s;
}

.chat-input-wrap {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 16px;
  z-index: 4;
  padding-top: 0;
  background: linear-gradient(180deg, rgba(248, 251, 255, 0) 0%, rgba(244, 248, 252, 0.62) 24%, rgba(239, 245, 251, 0.94) 100%);
}

.chat-input-card {
  border-radius: 28px;
  padding: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(245, 249, 254, 0.98)),
    linear-gradient(135deg, rgba(126, 205, 255, 0.08), transparent 44%);
  border: 1px solid rgba(194, 211, 229, 0.42);
  box-shadow:
    0 18px 36px rgba(165, 185, 210, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.chat-input-card :deep(.el-textarea__inner) {
  min-height: 52px !important;
  padding: 14px 148px 14px 14px !important;
  background: rgba(251, 253, 255, 0.92) !important;
  box-shadow:
    0 0 0 1px rgba(190, 208, 228, 0.52) inset,
    0 0 0 3px rgba(255, 120, 120, 0.05) !important;
  color: #22354c !important;
}

.composer-box {
  position: relative;
}

.chat-input-meta {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  margin-bottom: 8px;
}

.chat-session-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: fit-content;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(220, 241, 255, 0.9);
  color: #4f85c1;
  font-size: 12px;
  font-weight: 800;
}

.chat-input-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hint-badge {
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(228, 243, 255, 0.92);
  color: #5d86b1;
  font-size: 11px;
  font-weight: 700;
}

.send-btn {
  min-width: 112px;
}

.send-btn-inside {
  position: absolute;
  right: 10px;
  bottom: 10px;
  min-width: 118px;
  height: 40px;
  border-radius: 14px;
  z-index: 2;
  background: linear-gradient(135deg, #d8efff, #b9dbff) !important;
  border: 1px solid rgba(255, 124, 124, 0.24) !important;
  box-shadow:
    0 10px 20px rgba(162, 185, 214, 0.18),
    0 0 0 1px rgba(255, 255, 255, 0.58) inset !important;
}

@keyframes pulse {
  0%, 80%, 100% {
    transform: scale(1);
    opacity: 0.45;
  }
  40% {
    transform: scale(1.2);
    opacity: 1;
  }
}

@keyframes cursorBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

@keyframes thinkingPulse {
  0%, 100% { opacity: 0.7; }
  50% { opacity: 1; }
}

@keyframes thinkingSpin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

@keyframes glowBreath {
  0%, 100% {
    opacity: 0.72;
    transform: translate(-50%, -50%) scale(0.98);
  }
  50% {
    opacity: 0.94;
    transform: translate(-50%, -50%) scale(1.06);
  }
}

@keyframes iconAura {
  0%, 100% {
    opacity: 0.88;
    filter:
      drop-shadow(0 0 10px rgba(255, 255, 255, 0.4))
      drop-shadow(0 0 22px rgba(115, 130, 255, 0.3))
      drop-shadow(0 0 42px rgba(115, 130, 255, 0.22));
  }
  50% {
    opacity: 1;
    filter:
      drop-shadow(0 0 12px rgba(255, 255, 255, 0.56))
      drop-shadow(0 0 28px rgba(115, 130, 255, 0.42))
      drop-shadow(0 0 52px rgba(115, 130, 255, 0.3));
  }
}

@keyframes float {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}

.animate-float {
  animation: float 3s ease-in-out infinite;
}

@media (max-width: 1100px) {
  .chat-page,
  .chat-shell {
    overflow: visible;
  }

  .chat-shell {
    padding: 12px;
    height: auto;
    grid-template-rows: auto auto minmax(0, 1fr) auto;
  }

  .chat-toolbar,
  .chat-input-meta,
  .retrieval-actions {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar-left,
  .chat-header-actions {
    width: 100%;
  }

  .chat-header-actions {
    flex-direction: column;
  }

  .retrieval-grid {
    grid-template-columns: 1fr;
  }

  .kb-select {
    width: 100%;
  }

  .message-list {
    width: 100%;
  }

  .chat-main {
    padding-bottom: 12px;
  }

  .chat-input-wrap {
    position: sticky;
    left: auto;
    right: auto;
    bottom: 0;
  }

  .message-stack {
    max-width: calc(100% - 54px);
  }

  .quick-prompts {
    flex-direction: column;
    width: 100%;
    max-width: 420px;
  }

  .chat-input-card :deep(.el-textarea__inner) {
    padding-right: 14px !important;
    padding-bottom: 64px !important;
  }

  .send-btn-inside {
    left: 10px;
    right: 10px;
    width: calc(100% - 20px);
    min-width: 0;
  }

  .prompt-chip {
    width: 100%;
  }

  .reference-top,
  .setting-inline {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
