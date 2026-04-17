<template>
  <section class="chat-page">
    <div class="chat-shell glass-panel">
      <header class="chat-toolbar">
        <div class="toolbar-left">
          <span class="soft-chip">
            <span class="chip-dot"></span>
            智能问答
          </span>
          <span class="toolbar-desc">基于知识库进行检索问答</span>
        </div>

        <div class="chat-header-actions">
          <el-select
            v-model="selectedKbId"
            class="kb-select"
            placeholder="请选择知识库"
            @change="handleKnowledgeBaseChange"
          >
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>

          <el-button plain class="ghost-btn" @click="resetConversation">
            <el-icon><Delete /></el-icon>
            新对话
          </el-button>
        </div>
      </header>

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
          <p>输入你的问题后，我会像标准聊天助手一样连续回答，并保留历史上下文。</p>

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
          <article
            v-for="(message, index) in messages"
            :key="`${message.role}-${index}-${message.createdAt || ''}`"
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

              <div class="message-bubble" v-html="renderMarkdown(message.content)"></div>

              <div
                v-if="message.role === 'assistant' && hasReferenceSource(message)"
                class="reference-panel"
              >
                <div class="reference-header">
                  <span>参考来源</span>
                </div>
                <div class="reference-list">
                  <div v-for="(reference, refIndex) in message.references" :key="refIndex" class="reference-item">
                    <span class="reference-index">{{ refIndex + 1 }}</span>
                    <div class="reference-copy">
                      <strong>{{ reference.documentName || `文档 #${reference.docId}` }}</strong>
                      <span>{{ reference.chunkText }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="message.role === 'assistant'" class="message-actions">
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

          <article v-if="isLoading" class="message-row assistant">
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
                <span>思考中</span>
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
              placeholder="例如：总结这套知识库里关于部署步骤、接口说明和常见异常的核心要点。"
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
              {{ isLoading ? '生成中' : '发送问题' }}
            </el-button>
          </div>

          <div class="chat-input-actions">
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
import { chatApi, kbApi } from '@/api'
import { removeRecentSession, upsertRecentSession } from '@/utils/chatSessions'
import {
  CloseBold,
  CopyDocument,
  Delete,
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

const route = useRoute()
const router = useRouter()

const question = ref('')
const isLoading = ref(false)
const selectedKbId = ref(null)
const knowledgeBases = ref([])
const messages = ref([])
const sessionId = ref('')
const messagesContainer = ref(null)
const messagesEndRef = ref(null)
const isSendingFirstMessage = ref(false)

const exampleQuestions = [
  { title: '快速开始', text: '这个知识库能帮我解决哪些典型问题？' },
  { title: '文档梳理', text: '帮我总结和部署相关的关键文档内容。' },
  { title: '支持帮助', text: '如果我要上传新文档，完整流程应该怎么走？' }
]

const currentKnowledgeBaseName = computed(() => {
  return knowledgeBases.value.find((item) => item.id === selectedKbId.value)?.name || ''
})

function generateSessionId() {
  return `sess_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function renderMarkdown(content) {
  return content ? marked.parse(content) : ''
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

function buildReferenceSummary(kbId, references) {
  if (!Array.isArray(references) || !references.length) {
    return []
  }

  const knowledgeBase = knowledgeBases.value.find((item) => String(item.id) === String(kbId))
  if (!knowledgeBase) {
    return []
  }

  return [{
    documentName: knowledgeBase.name || '当前知识库',
    chunkText: knowledgeBase.description || '已命中当前知识库中的相关内容。'
  }]
}

function normalizeMessage(message) {
  return {
    kbId: message.kbId ?? null,
    role: String(message.role || '').toLowerCase(),
    content: message.content || '',
    createdAt: message.createdAt || '',
    references: buildReferenceSummary(message.kbId, normalizeReferences(message)),
    liked: false,
    disliked: false
  }
}

function hasReferenceSource(message) {
  return Array.isArray(message.references) && message.references.length > 0
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
  question.value = ''
  messages.value.push({
    kbId: selectedKbId.value,
    role: 'user',
    content,
    createdAt: new Date().toISOString()
  })
  syncSessionSummary()
  await scrollToBottom()

  isLoading.value = true

  try {
    const response = await chatApi.chat({
      kbId: selectedKbId.value,
      sessionId: sessionId.value,
      question: content
    })
    const answer = response.data?.answer?.trim()

    if (!answer) {
      throw new Error('模型暂时没有返回有效回答，请稍后重试')
    }

    messages.value.push({
      kbId: selectedKbId.value,
      role: 'assistant',
      content: response.data?.answer || '暂时没有拿到有效回答。',
      references: buildReferenceSummary(selectedKbId.value, response.data?.references || []),
      createdAt: new Date().toISOString()
    })
    syncSessionSummary()
  } catch (error) {
    ElMessage.error(error.message || '获取回答失败')
    messages.value.push({
      kbId: selectedKbId.value,
      role: 'assistant',
      content: '抱歉，这次回答生成失败了。你可以稍后重试，或者换一个更明确的问题继续问我。',
      createdAt: new Date().toISOString()
    })
  } finally {
    isLoading.value = false
    isSendingFirstMessage.value = false
    await scrollToBottom()
  }
}

function handleKnowledgeBaseChange() {
  if (!messages.value.length) {
    return
  }
  syncSessionSummary()
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
  if (!target) return

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
}

.chat-shell {
  height: 100%;
  min-height: 0;
  padding: 16px;
  border-radius: var(--radius-xl);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 14px;
  overflow: hidden;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-desc {
  color: var(--text-secondary);
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

.chat-main {
  min-height: 0;
  overflow: auto;
  padding: 4px 8px 0;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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
  color: #ffffff;
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
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.prompt-chip:hover {
  color: var(--text-primary);
  border-color: rgba(91, 108, 255, 0.22);
  background: rgba(91, 108, 255, 0.08);
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
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.96), rgba(141, 151, 255, 0.74));
  color: #ffffff;
}

.user-avatar {
  background: linear-gradient(135deg, rgba(34, 197, 94, 0.18), rgba(34, 197, 94, 0.32));
  color: #bbf7d0;
}

.message-stack {
  max-width: min(720px, calc(100% - 54px));
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-stack.user {
  align-items: flex-end;
}

.message-meta {
  display: flex;
  gap: 10px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.message-bubble {
  padding: 16px 18px;
  border-radius: 22px;
  line-height: 1.8;
  font-size: 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: var(--shadow-xs);
}

.message-stack.assistant .message-bubble {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-primary);
  border-top-left-radius: 10px;
}

.message-stack.user .message-bubble {
  background: linear-gradient(135deg, #1f9d55, #22c55e);
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.04);
  border-top-right-radius: 10px;
}

.message-bubble :deep(pre) {
  overflow: auto;
  padding: 14px;
  border-radius: 16px;
  background: #0f172a;
}

.message-bubble :deep(code) {
  font-family: 'Consolas', 'Courier New', monospace;
}

.reference-panel {
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(91, 108, 255, 0.14);
  overflow: hidden;
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

.reference-header span {
  font-size: 0;
}

.reference-header span::before {
  content: '参考来源';
  font-size: 12px;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
}

.reference-item {
  display: block;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.reference-index {
  display: none;
}

.reference-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.reference-copy strong {
  font-size: 12px;
}

.reference-copy span {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
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
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
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
  min-height: 0;
  align-self: stretch;
  padding-top: 2px;
  background: linear-gradient(180deg, rgba(13, 20, 34, 0) 0%, rgba(13, 20, 34, 0.4) 24%, rgba(13, 20, 34, 0.92) 100%);
}

.chat-input-card {
  border-radius: 28px;
  padding: 12px;
  background: rgba(18, 27, 44, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: var(--shadow-md);
}

.chat-input-card :deep(.el-textarea__inner) {
  min-height: 52px !important;
  padding: 14px 148px 14px 14px !important;
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
  background: rgba(91, 108, 255, 0.08);
  color: var(--primary-strong);
  font-size: 12px;
  font-weight: 800;
}

.chat-input-actions {
  display: none;
}

.chat-input-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hint-badge {
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-secondary);
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
  }

  .chat-toolbar,
  .chat-input-meta,
  .chat-input-actions {
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

  .kb-select {
    width: 100%;
  }

  .message-list {
    width: 100%;
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
}
</style>
