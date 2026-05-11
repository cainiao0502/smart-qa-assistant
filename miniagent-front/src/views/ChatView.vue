<template>
  <section class="chat-page">
    <div class="chat-shell glass-panel">
      <header class="chat-toolbar">
        <div class="toolbar-left">
          <span class="soft-chip">
            <span class="chip-dot"></span>
            智能问答
          </span>
          <span class="toolbar-desc">{{ toolbarDescription }}</span>
        </div>

        <div class="chat-header-actions">
          <div class="mode-switch" v-if="!isKnowledgeBaseMode">
            <button
              type="button"
              class="mode-chip"
              :class="{ active: assistantMode === 'fast' }"
              @click="assistantMode = 'fast'"
            >
              快速对话
            </button>
            <button
              type="button"
              class="mode-chip"
              :class="{ active: assistantMode === 'agent' }"
              @click="assistantMode = 'agent'"
            >
              Agent增强
            </button>
          </div>

          <el-select
            v-model="selectedKbId"
            class="kb-select"
            placeholder="通用助手（不使用知识库）"
            clearable
          >
            <el-option
              label="通用助手（不使用知识库）"
              :value="null"
            />
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>

          <el-button plain class="ghost-btn" @click="toggleRetrievalSettings">
            <el-icon><Operation /></el-icon>
            {{ isKnowledgeBaseMode ? '检索设置' : '对话设置' }}
          </el-button>

          <el-button plain class="ghost-btn" @click="resetConversation">
            <el-icon><Delete /></el-icon>
            新对话
          </el-button>
        </div>
      </header>

      <RetrievalSettings
        :visible="showRetrievalSettings"
        :retrieval-options="retrievalOptions"
        @update:retrieval-options="retrievalOptions = $event"
        v-model:selected-skill-names="selectedSkillNames"
        :available-skills="availableSkills"
        :available-documents="availableDocuments"
        :is-knowledge-base-mode="isKnowledgeBaseMode"
        :selected-kb-id="selectedKbId"
        @reset="resetRetrievalOptions"
      />

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
          <p>不选知识库时可在快速对话和 Agent 增强之间切换；选择知识库后会自动进入知识库问答。</p>

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
                  v-if="message.role === 'assistant' && (hasAgentSteps(message) || (message.agentPlan && message.agentPlan.length))"
                  class="assistant-aux-bubble execution-bubble-shell"
                >
                  <div class="execution-bubble-meta">
                    <div class="execution-bubble-meta-left">
                      <span class="execution-bubble-kicker">执行编排</span>
                      <code v-if="message.runId" class="run-inline-code">{{ shortRunId(message.runId) }}</code>
                    </div>
                    <div class="execution-bubble-meta-right">
                      <small>{{ getExecutionProgressMeta(message).summary }}</small>
                      <button
                        type="button"
                        class="execution-collapse-btn"
                        @click="toggleExecutionPanel(message)"
                      >
                        {{ isExecutionPanelExpanded(message) ? '收起' : '展开' }}
                      </button>
                    </div>
                  </div>
                  <div v-if="isExecutionPanelExpanded(message)" class="agent-step-panel">
                    <div class="agent-step-header">
                      <div class="agent-step-header-left">
                        <span>任务流水</span>
                      </div>
                      <small>{{ getExecutionProgressMeta(message).summary }}</small>
                    </div>
                    <div class="execution-progress-strip">
                      <div class="execution-progress-copy">
                        <strong>{{ getExecutionProgressMeta(message).headline }}</strong>
                        <small>{{ getExecutionProgressMeta(message).subline }}</small>
                      </div>
                      <div class="execution-progress-bar">
                        <span class="execution-progress-bar-fill" :style="{ width: `${getExecutionProgressMeta(message).ratio}%` }"></span>
                      </div>
                    </div>
                    <div class="execution-current-task" v-if="getCurrentExecutionTask(message)">
                      <span class="execution-current-label">当前步骤</span>
                      <strong>{{ getCurrentExecutionTask(message).title }}</strong>
                      <small>{{ getCurrentExecutionTask(message).subtitle }}</small>
                    </div>
                    <div class="execution-task-list">
                      <div
                      v-for="task in buildExecutionTasks(message)"
                      :key="task.key"
                      class="execution-task-item"
                      :class="[
                        `status-${String(task.status || '').toLowerCase()}`,
                        { current: isCurrentExecutionTask(message, task) }
                      ]"
                      >
                        <div class="execution-task-index">{{ task.order }}</div>
                        <div class="execution-task-main">
                          <div class="execution-task-top">
                            <div class="execution-task-copy">
                              <div class="execution-task-title-row">
                                <strong>{{ task.title }}</strong>
                                <span v-if="task.planOrigin === 'appended'" class="task-origin-badge">执行中补充</span>
                              </div>
                              <span v-if="task.subtitle" class="execution-task-subtitle">{{ task.subtitle }}</span>
                            </div>
                            <div class="agent-step-badges">
                              <span class="tool-badge" :class="getToolStatusClass(task.status)">
                                {{ formatToolStatus(task.status) }}
                              </span>
                              <span v-if="task.durationMs !== undefined && task.durationMs !== null" class="tool-badge">
                                {{ task.durationMs }}ms
                              </span>
                            </div>
                          </div>
                        <span v-if="task.detail" class="agent-step-summary">{{ task.detail }}</span>
                        <pre
                          v-if="task.arguments && Object.keys(task.arguments).length"
                          class="tool-arguments run-detail-arguments"
                        >{{ formatToolArguments(task.arguments) }}</pre>
                        <div v-if="task.toolCalls && task.toolCalls.length" class="task-tool-tree">
                          <div class="task-tool-tree-header">
                            <span>关联工具</span>
                            <small>{{ task.toolCalls.length }} 次调用</small>
                          </div>
                          <div class="task-tool-tree-list">
                            <div v-for="(toolCall, toolIndex) in task.toolCalls" :key="`${task.key}-tool-${toolIndex}`" class="task-tool-tree-item">
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
                        <div v-if="task.rawSteps && task.rawSteps.length" class="task-raw-step-block">
                          <button
                            type="button"
                            class="task-raw-step-toggle"
                            @click="toggleTaskRawSteps(message, task.key)"
                          >
                            <span>{{ isTaskRawStepsExpanded(message, task.key) ? '收起原始步骤' : '查看原始步骤' }}</span>
                            <small>{{ task.rawSteps.length }} 条</small>
                          </button>
                          <div v-if="isTaskRawStepsExpanded(message, task.key)" class="task-raw-step-list">
                            <div
                              v-for="(step, stepIndex) in task.rawSteps"
                              :key="`${task.key}-raw-${step.stepIndex || stepIndex}`"
                              class="agent-step-item task-raw-step-item"
                            >
                              <div class="agent-step-top">
                                <div class="agent-step-title-block">
                                  <strong>步骤 {{ step.stepIndex || (stepIndex + 1) }}</strong>
                                  <span class="agent-step-subtitle">{{ formatAgentStepLabel(step) }}</span>
                                </div>
                                <div class="agent-step-badges">
                                  <span class="tool-badge" :class="getToolStatusClass(step.status)">
                                    {{ formatToolStatus(step.status) }}
                                  </span>
                                  <span v-if="step.durationMs !== undefined && step.durationMs !== null" class="tool-badge">
                                    {{ step.durationMs }}ms
                                  </span>
                                </div>
                              </div>
                              <span v-if="step.reason" class="agent-step-reason">{{ step.reason }}</span>
                              <span v-if="step.observationSummary" class="agent-step-summary">{{ step.observationSummary }}</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                    <div
                      v-if="getExecutionOrphanTools(message).length"
                      class="task-tool-tree orphan-tool-tree"
                    >
                      <div class="task-tool-tree-header">
                        <span>未归类工具调用</span>
                        <small>{{ getExecutionOrphanTools(message).length }} 次</small>
                      </div>
                      <div class="task-tool-tree-list">
                        <div
                          v-for="(toolCall, toolIndex) in getExecutionOrphanTools(message)"
                          :key="`orphan-tool-${toolIndex}`"
                          class="task-tool-tree-item"
                        >
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
                        </div>
                      </div>
                    </div>
                    <div v-if="false && hasAgentSteps(message)" class="agent-step-list raw-step-list">
                      <div
                        v-for="(step, stepIndex) in message.agentSteps"
                        :key="`${step.stepIndex || stepIndex}-${step.stepType || 'step'}`"
                        class="agent-step-item"
                      >
                        <div class="agent-step-top">
                          <div class="agent-step-title-block">
                            <strong>步骤 {{ step.stepIndex || (stepIndex + 1) }}</strong>
                            <span class="agent-step-subtitle">
                              {{ formatAgentStepLabel(step) }}
                            </span>
                          </div>
                          <div class="agent-step-badges">
                            <span class="tool-badge" :class="getToolStatusClass(step.status)">
                              {{ formatToolStatus(step.status) }}
                            </span>
                            <span v-if="step.durationMs !== undefined && step.durationMs !== null" class="tool-badge">
                              {{ step.durationMs }}ms
                            </span>
                          </div>
                        </div>
                        <span v-if="step.reason" class="agent-step-reason">{{ step.reason }}</span>
                        <span v-if="step.observationSummary" class="agent-step-summary">{{ step.observationSummary }}</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div
                  v-if="message.role === 'assistant' && hasToolCalls(message) && !(hasAgentSteps(message) || (message.agentPlan && message.agentPlan.length))"
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
                  <button
                    v-if="message.runId"
                    type="button"
                    class="action-btn"
                    @click="openRunDetail(message)"
                  >
                    <el-icon><Operation /></el-icon>
                    运行详情
                  </button>
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

        <button
          v-if="showScrollToBottom"
          type="button"
          class="scroll-to-latest-btn"
          @click="scrollToBottom(true)"
        >
          回到底部
        </button>
      </main>

      <ChatInput
        :question="question"
        @update:question="question = $event"
        :is-loading="isLoading"
        :assistant-mode="assistantMode"
        :is-knowledge-base-mode="isKnowledgeBaseMode"
        :current-knowledge-base-name="currentKnowledgeBaseName"
        :selected-skill-names="selectedSkillNames"
        :selected-executable-skill-count="selectedExecutableSkillCount"
        :retrieval-options="retrievalOptions"
        :session-id="sessionId"
        @send="sendMessage"
        @stop="stopGenerating"
      />
    </div>

    <RunDetailDialog
      v-model:visible="runDetailDialogVisible"
      :loading="runDetailLoading"
      :detail="runDetail"
    />
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatApi, docApi, kbApi, skillApi } from '@/api'
import RunDetailDialog from '@/components/RunDetailDialog.vue'
import RetrievalSettings from '@/components/RetrievalSettings.vue'
import ChatInput from '@/components/ChatInput.vue'
import { useExecutionTasks } from '@/composables/useExecutionTasks'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { removeRecentSession, upsertRecentSession } from '@/utils/chatSessions'
import {
  CloseBold,
  CopyDocument,
  Delete,
  Operation,
  Select,
  User
} from '@element-plus/icons-vue'

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
const assistantMode = ref('fast')
const selectedSkillNames = ref([])
const availableSkills = ref([])
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
const currentAbortController = ref(null)
const autoScrollEnabled = ref(true)
const showRetrievalSettings = ref(false)
const retrievalOptions = ref(DEFAULT_RETRIEVAL_OPTIONS())
const runDetailDialogVisible = ref(false)
const runDetailLoading = ref(false)
const runDetail = ref(null)

const {
  buildExecutionTasks,
  getExecutionProgressMeta,
  getCurrentExecutionTask,
  isCurrentExecutionTask,
  getExecutionOrphanTools,
  isExecutionPanelExpanded,
  toggleExecutionPanel,
  isTaskRawStepsExpanded,
  toggleTaskRawSteps,
  getToolStatusClass,
  formatToolStatus,
  formatToolArguments,
  formatToolHeadline,
  formatToolSubtitle,
  formatAgentStepLabel,
  shortRunId,
  sortAgentSteps
} = useExecutionTasks()
const STREAM_CONNECT_TIMEOUT_MS = 30000
const STREAM_IDLE_TIMEOUT_MS = 600000
const SESSION_SYNC_DELAY_MS = 180

const fileTypeOptions = ['pdf', 'txt', 'md', 'doc', 'docx']

const exampleQuestions = [
  { title: '快速开始', text: '这个知识库能帮我解决哪些典型问题？' },
  { title: '文档梳理', text: '帮我总结和部署相关的关键文档内容。' },
  { title: '使用帮助', text: '如果我要上传新文档，完整流程应该怎么走？' }
]

const currentKnowledgeBaseName = computed(() => {
  return knowledgeBases.value.find((item) => item.id === selectedKbId.value)?.name || '通用助手'
})
const isKnowledgeBaseMode = computed(() => selectedKbId.value !== null && selectedKbId.value !== undefined)
const isAgentEnhancedMode = computed(() => isKnowledgeBaseMode.value || assistantMode.value === 'agent')
const toolbarDescription = computed(() => {
  if (isKnowledgeBaseMode.value) {
    return '基于知识库检索结果生成回答'
  }
  return assistantMode.value === 'agent'
    ? '通用助手 · Agent 增强模式'
    : '通用助手 · 快速对话模式'
})

const executableSkillCount = computed(() => {
  return availableSkills.value.filter((item) => item.executable).length
})

const selectedExecutableSkillCount = computed(() => {
  const selected = new Set(selectedSkillNames.value)
  return availableSkills.value.filter((item) => item.executable && selected.has(item.name)).length
})

const streamingMessageHasContent = computed(() => Boolean(streamingMessage.value?.content))
const showScrollToBottom = computed(() => !autoScrollEnabled.value && messages.value.length > 0)

const {
  initializeMarkdownRenderer,
  renderMarkdown,
  renderStreamMarkdown
} = useMarkdownRenderer()
let sessionSyncTimer = 0
let scrollStateFrame = 0

function generateSessionId() {
  return `sess_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
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

function normalizeAgentSteps(message) {
  if (Array.isArray(message.agentSteps)) {
    return message.agentSteps
  }

  return []
}

function normalizeAgentPlan(message) {
  if (Array.isArray(message.agentPlan)) {
    return message.agentPlan
  }
  return []
}

function normalizeCompletedTaskKeys(message) {
  if (Array.isArray(message.completedTaskKeys)) {
    return message.completedTaskKeys.filter((item) => typeof item === 'string' && item)
  }
  return []
}

function normalizeMessage(message) {
  const references = normalizeReferences(message)
  const toolCalls = normalizeToolCalls(message)
  const agentSteps = normalizeAgentSteps(message)
  const agentPlan = normalizeAgentPlan(message)
  const completedTaskKeys = normalizeCompletedTaskKeys(message)
  return {
    kbId: message.kbId ?? null,
    runId: message.runId || '',
    role: String(message.role || '').toLowerCase(),
    content: message.content || '',
    createdAt: message.createdAt || '',
    retrievalConfig: message.retrievalConfig || null,
    references,
    toolCalls,
    agentPlan,
    currentActionKey: message.currentActionKey || '',
    completedTaskKeys,
    agentSteps,
    runStatus: message.runStatus || '',
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

function hasAgentSteps(message) {
  return Array.isArray(message.agentSteps) && message.agentSteps.length > 0
}

function upsertAgentStep(message, stepPayload) {
  if (!message || !stepPayload || typeof stepPayload !== 'object') {
    return
  }

  const normalizedStep = {
    ...stepPayload,
    stepIndex: stepPayload.stepIndex ?? ((message.agentSteps?.length || 0) + 1)
  }
  const nextSteps = Array.isArray(message.agentSteps) ? [...message.agentSteps] : []
  const existingIndex = nextSteps.findIndex((item) => (item?.stepIndex ?? -1) === normalizedStep.stepIndex)

  if (existingIndex >= 0) {
    nextSteps[existingIndex] = {
      ...nextSteps[existingIndex],
      ...normalizedStep
    }
  } else {
    nextSteps.push(normalizedStep)
  }

  message.agentSteps = sortAgentSteps(nextSteps)
}

function applyRunStatus(message, payload) {
  if (!message || !payload || typeof payload !== 'object') {
    return
  }
  if (payload.runId) {
    message.runId = payload.runId
  }
  if (payload.status) {
    message.runStatus = payload.status
  }
}

function applyAgentPlan(message, payload) {
  if (!message || !payload || typeof payload !== 'object') {
    return
  }
  if (payload.runId) {
    message.runId = payload.runId
  }
  if (Array.isArray(payload.tasks)) {
    message.agentPlan = payload.tasks
  }
  if (typeof payload.currentActionKey === 'string') {
    message.currentActionKey = payload.currentActionKey
  }
  if (Array.isArray(payload.completedTaskKeys)) {
    message.completedTaskKeys = payload.completedTaskKeys.filter((item) => typeof item === 'string' && item)
  }
}

function shouldRenderMessage(message) {
  if (!message || !message.role) {
    return false
  }
  if (message.role !== 'assistant') {
    return true
  }
  return Boolean(
    message.content
    || hasReferenceSource(message)
    || hasToolCalls(message)
    || (Array.isArray(message.agentPlan) && message.agentPlan.length > 0)
    || hasAgentSteps(message)
    || message !== streamingMessage.value
  )
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

function updateAutoScrollState() {
  if (!messagesContainer.value) {
    autoScrollEnabled.value = true
    return
  }
  const container = messagesContainer.value
  const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight
  autoScrollEnabled.value = distanceToBottom < 120
}

function scheduleAutoScrollStateUpdate() {
  if (scrollStateFrame) {
    return
  }
  scrollStateFrame = window.requestAnimationFrame(() => {
    scrollStateFrame = 0
    updateAutoScrollState()
  })
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
  if (sessionSyncTimer) {
    window.clearTimeout(sessionSyncTimer)
  }
  sessionSyncTimer = window.setTimeout(() => {
    upsertRecentSession({
      id: sessionId.value,
      title: extractSessionTitle(messages.value),
      preview: extractSessionPreview(messages.value),
      kbId: selectedKbId.value,
      kbName: currentKnowledgeBaseName.value,
      updatedAt: new Date().toISOString()
    })
    sessionSyncTimer = 0
  }, SESSION_SYNC_DELAY_MS)
}

function flushSessionSummary() {
  if (sessionSyncTimer) {
    window.clearTimeout(sessionSyncTimer)
    sessionSyncTimer = 0
  }
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
}

async function fetchSkills() {
  const response = await skillApi.list()
  availableSkills.value = response.data || []
  const validSkills = new Set(availableSkills.value.map((item) => item.name))
  selectedSkillNames.value = selectedSkillNames.value.filter((name) => validSkills.has(name))
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
  selectedKbId.value = matchedKbId ?? null

  syncSessionSummary()
  await scrollToBottom(true)
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
  selectedKbId.value = null
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
    agentPlan: [],
    currentActionKey: '',
    completedTaskKeys: [],
    agentSteps: [],
    runId: '',
    runStatus: 'RUNNING',
    createdAt: new Date().toISOString(),
    liked: false,
    disliked: false
  }
}

async function openRunDetail(message) {
  if (!message?.runId) {
    return
  }

  await router.replace({
    path: '/',
    query: {
      ...route.query,
      session: sessionId.value || route.query.session,
      run: message.runId
    }
  })
}

async function openRunDetailById(runId) {
  if (!runId) {
    return
  }

  runDetailDialogVisible.value = true
  runDetailLoading.value = true
  runDetail.value = null

  try {
    const response = await chatApi.getRunDetail(runId)
    runDetail.value = response.data || null
  } catch (error) {
    ElMessage.error(error.message || '加载运行详情失败')
    runDetailDialogVisible.value = false
  } finally {
    runDetailLoading.value = false
  }
}

function buildChatPayload(content) {
  const hasKnowledgeBase = isKnowledgeBaseMode.value
  const documentIds = hasKnowledgeBase && retrievalOptions.value.documentIds.length ? retrievalOptions.value.documentIds : null
  const fileTypes = hasKnowledgeBase && retrievalOptions.value.fileTypes.length ? retrievalOptions.value.fileTypes : null

  return {
    kbId: selectedKbId.value,
    sessionId: sessionId.value,
    question: content,
    topK: hasKnowledgeBase ? retrievalOptions.value.topK : null,
    scoreThreshold: hasKnowledgeBase ? Number(retrievalOptions.value.scoreThreshold.toFixed(2)) : null,
    documentIds,
    fileTypes,
    documentNameKeyword: hasKnowledgeBase ? (retrievalOptions.value.documentNameKeyword.trim() || null) : null,
    skillNames: selectedSkillNames.value.length ? selectedSkillNames.value : null,
    agentEnabled: isAgentEnhancedMode.value
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
      case 'agent_step':
        handlers.onAgentStep?.(payload)
        break
      case 'agent_plan':
        handlers.onAgentPlan?.(payload)
        break
      case 'run_status':
        handlers.onRunStatus?.(payload)
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
  if (!question.value.trim() || isLoading.value) {
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
  await scrollToBottom(true)

  messages.value.push(createAssistantMessage())
  const assistantMessage = messages.value[messages.value.length - 1]
  streamingMessage.value = assistantMessage
  isLoading.value = true
  const abortController = new AbortController()
  currentAbortController.value = abortController
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

    hasReceivedStreamEvent = true
    resetStreamTimer()

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
      onAgentStep(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        upsertAgentStep(assistantMessage, streamPayload)
      },
      onAgentPlan(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        applyAgentPlan(assistantMessage, streamPayload)
      },
      onRunStatus(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        applyRunStatus(assistantMessage, streamPayload)
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
        if (streamPayload?.runId) {
          assistantMessage.runId = streamPayload.runId
        }
        if (streamPayload?.runStatus) {
          assistantMessage.runStatus = streamPayload.runStatus
        }
        if (Array.isArray(streamPayload?.agentPlan)) {
          assistantMessage.agentPlan = streamPayload.agentPlan
        }
        if (typeof streamPayload?.currentActionKey === 'string') {
          assistantMessage.currentActionKey = streamPayload.currentActionKey
        }
        if (Array.isArray(streamPayload?.completedTaskKeys)) {
          assistantMessage.completedTaskKeys = streamPayload.completedTaskKeys.filter((item) => typeof item === 'string' && item)
        }
        if (Array.isArray(streamPayload?.references)) {
          assistantMessage.references = streamPayload.references
        }
        if (Array.isArray(streamPayload?.toolCalls)) {
          assistantMessage.toolCalls = streamPayload.toolCalls
        }
        if (Array.isArray(streamPayload?.agentSteps)) {
          assistantMessage.agentSteps = sortAgentSteps(streamPayload.agentSteps)
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
    const isUserStopped = error?.message === 'user-stop'
    const fallbackMessage = hasReceivedStreamEvent
      ? '回答生成中断了，已保留当前已返回的内容。你可以继续追问，或重新发送一次。'
      : '回答等待超时了，当前没有收到模型返回内容。请稍后重试，或检查后端日志。'
    assistantMessage.content = assistantMessage.content || (isUserStopped ? '已停止生成。你可以继续补充问题，或重新发送一次。' : fallbackMessage)
    if (isUserStopped) {
      ElMessage.success('已停止生成')
    } else {
      ElMessage.error(isAbortError ? '流式回答超时，已停止等待' : (error.message || '获取回答失败'))
    }
  } finally {
    thinkingContent.value = ''
    if (streamTimer) {
      window.clearTimeout(streamTimer)
    }
    if (pendingFlushHandle.value) {
      window.cancelAnimationFrame(pendingFlushHandle.value)
      flushStreamDelta()
    }
    currentAbortController.value = null
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
  selectedSkillNames.value = []
}

function useExample(text) {
  question.value = text
}

async function resetConversation() {
  stopGenerating()
  messages.value = []
  question.value = ''
  sessionId.value = ''
  selectedKbId.value = null
  assistantMode.value = 'fast'
  await router.replace({ path: '/' })
}

function stopGenerating() {
  currentAbortController.value?.abort(new DOMException('user-stop', 'AbortError'))
}

async function scrollToBottom(force = false) {
  await nextTick()
  requestAnimationFrame(() => {
    if (!force && !autoScrollEnabled.value) {
      return
    }
    if (messagesEndRef.value?.scrollIntoView) {
      messagesEndRef.value.scrollIntoView({ block: 'end' })
    }

    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      autoScrollEnabled.value = true
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
  () => route.query.run,
  async (value, oldValue) => {
    if (value === oldValue) return
    if (typeof value === 'string' && value) {
      await openRunDetailById(value)
      return
    }
    runDetailDialogVisible.value = false
    runDetail.value = null
  }
)

watch(
  () => runDetailDialogVisible.value,
  async (visible) => {
    if (visible) return
    if (route.query.run) {
      const nextQuery = { ...route.query }
      delete nextQuery.run
      await router.replace({ path: '/', query: nextQuery })
    }
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

watch(
  () => streamingMessage.value?.content,
  async () => {
    if (!streamingMessage.value?.content) {
      return
    }
    await scrollToBottom()
  },
  { flush: 'post' }
)

onMounted(async () => {
  messagesContainer.value?.addEventListener('scroll', scheduleAutoScrollStateUpdate, { passive: true })
  try {
    initializeMarkdownRenderer().catch(() => {
      ElMessage.warning('Markdown 渲染组件加载稍慢，已先使用轻量预览')
    })
    await fetchKnowledgeBases()
    await fetchSkills()
    await initializeSessionFromRoute()
    if (typeof route.query.run === 'string' && route.query.run) {
      await openRunDetailById(route.query.run)
    }
  } catch (error) {
    ElMessage.error(error.message || '初始化聊天页面失败')
  }
})

onBeforeUnmount(() => {
  messagesContainer.value?.removeEventListener('scroll', scheduleAutoScrollStateUpdate)
  if (pendingFlushHandle.value) {
    window.cancelAnimationFrame(pendingFlushHandle.value)
  }
  if (scrollStateFrame) {
    window.cancelAnimationFrame(scrollStateFrame)
  }
  flushSessionSummary()
  stopGenerating()
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

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(193, 208, 225, 0.9);
  box-shadow: 0 8px 18px rgba(170, 188, 210, 0.12);
}

.mode-chip {
  border: 0;
  background: transparent;
  color: #5f6f85;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast), box-shadow var(--transition-fast);
}

.mode-chip.active {
  background: linear-gradient(135deg, rgba(219, 238, 255, 0.98), rgba(204, 228, 255, 0.98));
  color: #1f4b7b;
  box-shadow: inset 0 0 0 1px rgba(152, 187, 224, 0.82);
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

.skill-option-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.skill-option-name {
  min-width: 0;
  color: #24364d;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-setting-tip {
  margin-top: 8px;
  color: #7a8ca4;
  font-size: 11px;
  line-height: 1.5;
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
  contain: layout paint;
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
  contain: layout;
}

.messages-end-anchor {
  width: 100%;
  height: 1px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  content-visibility: auto;
  contain-intrinsic-size: 220px;
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

.assistant-aux-bubble {
  position: relative;
  border-radius: 24px;
  border: 1px solid rgba(188, 207, 229, 0.55);
  background:
    linear-gradient(180deg, rgba(239, 246, 255, 0.96), rgba(247, 251, 255, 0.98)),
    radial-gradient(circle at top left, rgba(122, 188, 255, 0.12), transparent 38%);
  box-shadow:
    0 18px 36px rgba(159, 181, 208, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.84);
  overflow: hidden;
}

.execution-bubble-shell {
  gap: 0;
}

.execution-bubble-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  background: linear-gradient(180deg, rgba(228, 240, 255, 0.84), rgba(240, 247, 255, 0.76));
  border-bottom: 1px solid rgba(188, 207, 229, 0.45);
}

.execution-bubble-meta-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.execution-bubble-meta-right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.execution-collapse-btn {
  border: 0;
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(236, 243, 250, 0.92);
  color: #5d738d;
  font-size: 11px;
  font-weight: 800;
  cursor: pointer;
}

.execution-collapse-btn:hover {
  background: rgba(227, 237, 248, 0.98);
}

.execution-bubble-kicker {
  display: inline-flex;
  align-items: center;
  padding: 4px 9px;
  border-radius: 999px;
  background: rgba(77, 141, 255, 0.12);
  color: #4b72b4;
  font-size: 11px;
  font-weight: 800;
}

.execution-bubble-meta small {
  color: #6f86a1;
  font-size: 11px;
  font-weight: 700;
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
    linear-gradient(180deg, rgba(252, 254, 255, 0.99), rgba(245, 249, 253, 0.99)),
    linear-gradient(135deg, rgba(143, 185, 227, 0.08), transparent 44%);
  border: 1px solid rgba(188, 204, 222, 0.92);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.72),
    0 12px 28px rgba(142, 164, 191, 0.12);
  margin: 12px 0;
  color: #22364f;
}

.message-bubble :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  font-variant-ligatures: common-ligatures;
}

.message-bubble :deep(pre code),
.message-bubble :deep(.hljs) {
  background: transparent;
  color: #22364f;
}

.message-bubble :deep(.hljs-comment),
.message-bubble :deep(.hljs-quote) {
  color: #6f8096;
}

.message-bubble :deep(.hljs-keyword),
.message-bubble :deep(.hljs-selector-tag),
.message-bubble :deep(.hljs-built_in),
.message-bubble :deep(.hljs-name),
.message-bubble :deep(.hljs-tag) {
  color: #8b3fd1;
}

.message-bubble :deep(.hljs-string),
.message-bubble :deep(.hljs-attr),
.message-bubble :deep(.hljs-template-tag),
.message-bubble :deep(.hljs-template-variable) {
  color: #176d52;
}

.message-bubble :deep(.hljs-number),
.message-bubble :deep(.hljs-literal),
.message-bubble :deep(.hljs-symbol),
.message-bubble :deep(.hljs-bullet) {
  color: #0f6aa8;
}

.message-bubble :deep(.hljs-title),
.message-bubble :deep(.hljs-section),
.message-bubble :deep(.hljs-type) {
  color: #1c4f91;
}

.message-bubble :deep(.language-mermaid),
.message-bubble :deep(pre code.language-mermaid) {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
  color: #1f334a;
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
  content-visibility: auto;
  contain-intrinsic-size: 120px;
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

.agent-step-panel {
  border-radius: 0;
  background: transparent;
  border: 0;
  overflow: hidden;
}

.agent-step-header,
.agent-step-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.agent-step-header-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.run-inline-code {
  display: inline-flex;
  align-items: center;
  max-width: 220px;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(236, 243, 250, 0.92);
  border: 1px solid rgba(209, 221, 235, 0.7);
  color: #5d738d;
  font-size: 10px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-step-header {
  padding: 12px 14px;
  border-bottom: 1px solid rgba(91, 108, 255, 0.08);
  font-size: 12px;
  font-weight: 800;
}

.agent-step-header small {
  color: var(--text-muted);
  font-size: 11px;
}

.execution-progress-strip {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(91, 108, 255, 0.08);
  background: linear-gradient(180deg, rgba(252, 254, 255, 0.95), rgba(246, 250, 255, 0.92));
}

.execution-progress-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.execution-progress-copy strong {
  color: #24364d;
  font-size: 13px;
  font-weight: 800;
}

.execution-progress-copy small {
  color: #70839c;
  font-size: 11px;
  line-height: 1.5;
}

.execution-progress-bar {
  position: relative;
  width: 100%;
  height: 8px;
  border-radius: 999px;
  background: rgba(209, 221, 235, 0.62);
  overflow: hidden;
}

.execution-progress-bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: linear-gradient(90deg, #67b6ff, #5b6cff);
  transition: width 180ms ease;
}

.agent-step-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
}

.execution-current-task {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(91, 108, 255, 0.08);
  background: linear-gradient(180deg, rgba(244, 248, 255, 0.92), rgba(249, 251, 255, 0.92));
}

.execution-current-label {
  color: #7a8ca4;
  font-size: 11px;
  font-weight: 700;
}

.execution-current-task strong {
  color: #24364d;
  font-size: 14px;
  font-weight: 800;
}

.execution-current-task small {
  color: #6d819a;
  font-size: 12px;
  line-height: 1.5;
}

.execution-task-list {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 14px;
}

.execution-task-list::before {
  content: '';
  position: absolute;
  left: 27px;
  top: 18px;
  bottom: 18px;
  width: 2px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(170, 194, 224, 0.8), rgba(208, 223, 241, 0.35));
  pointer-events: none;
}

.execution-task-item {
  position: relative;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 12px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(197, 214, 233, 0.72);
  transition:
    transform var(--transition-fast),
    box-shadow var(--transition-fast),
    border-color var(--transition-fast),
    background var(--transition-fast);
}

.execution-task-item.status-running {
  border-color: rgba(245, 158, 11, 0.28);
  box-shadow: inset 0 0 0 1px rgba(245, 158, 11, 0.12);
}

.execution-task-item.status-success {
  border-color: rgba(34, 197, 94, 0.2);
  animation: taskSuccessGlow 520ms ease-out;
}

.execution-task-item.status-failed {
  border-color: rgba(239, 68, 68, 0.22);
}

.execution-task-item.status-partial {
  border-color: rgba(245, 158, 11, 0.24);
  background: linear-gradient(180deg, rgba(255, 251, 243, 0.94), rgba(255, 255, 255, 0.82));
}

.execution-task-item.status-unfinished {
  border-style: dashed;
  background: rgba(250, 252, 255, 0.74);
}

.execution-task-item.current {
  transform: translateY(-1px);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(246, 250, 255, 0.96));
  box-shadow:
    0 12px 28px rgba(157, 181, 209, 0.14),
    inset 0 0 0 1px rgba(118, 165, 235, 0.12);
}

.execution-task-item.current::after {
  content: '';
  position: absolute;
  left: 8px;
  top: 50%;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(77, 141, 255, 0.9);
  box-shadow:
    0 0 0 6px rgba(77, 141, 255, 0.12),
    0 0 0 12px rgba(77, 141, 255, 0.06);
  transform: translateY(-50%);
  animation: executionPulse 1.8s ease-out infinite;
  pointer-events: none;
}

.execution-task-index {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(91, 108, 255, 0.1);
  color: #4d63d4;
  font-size: 12px;
  font-weight: 800;
  position: relative;
  z-index: 1;
  box-shadow: 0 0 0 4px rgba(241, 247, 255, 0.96);
}

.execution-task-item.status-success .execution-task-index {
  background: rgba(34, 197, 94, 0.14);
  color: #1f9b58;
}

.execution-task-item.status-running .execution-task-index,
.execution-task-item.current .execution-task-index {
  background: rgba(77, 141, 255, 0.16);
  color: #356fcb;
}

.execution-task-item.status-failed .execution-task-index {
  background: rgba(239, 68, 68, 0.14);
  color: #d54949;
}

.execution-task-item.status-partial .execution-task-index {
  background: rgba(245, 158, 11, 0.16);
  color: #d2831d;
}

.execution-task-item.status-unfinished .execution-task-index {
  background: rgba(148, 163, 184, 0.14);
  color: #6f8198;
}

.execution-task-main,
.execution-task-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.execution-task-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.execution-task-copy strong {
  color: #24364d;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.45;
}

.execution-task-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.task-origin-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(91, 108, 255, 0.1);
  color: #5972b5;
  font-size: 10px;
  font-weight: 800;
  line-height: 1.4;
}

.execution-task-subtitle {
  color: #7a8ca4;
  font-size: 11px;
  line-height: 1.5;
}

.raw-step-list {
  border-top: 1px dashed rgba(194, 211, 228, 0.5);
  padding-top: 10px;
}

.raw-step-list .agent-step-item {
  background: rgba(255, 255, 255, 0.42);
}

.task-tool-tree,
.task-raw-step-block {
  margin-top: 10px;
  border-radius: 14px;
  background: rgba(246, 250, 255, 0.8);
  border: 1px solid rgba(203, 217, 234, 0.66);
  overflow: hidden;
}

.orphan-tool-tree {
  margin: 0 14px 14px;
}

.task-tool-tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  background: rgba(236, 244, 255, 0.84);
  border-bottom: 1px solid rgba(203, 217, 234, 0.58);
}

.task-tool-tree-header span,
.task-tool-tree-header small {
  color: #5f7896;
  font-size: 11px;
  font-weight: 800;
}

.task-tool-tree-list,
.task-raw-step-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px 12px;
}

.task-tool-tree-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(207, 220, 236, 0.72);
}

.task-tool-tree-item::before {
  content: '';
  position: absolute;
  left: -7px;
  top: 18px;
  width: 10px;
  height: 2px;
  border-radius: 999px;
  background: rgba(179, 198, 223, 0.82);
}

.task-raw-step-toggle {
  width: 100%;
  border: 0;
  background: rgba(236, 244, 255, 0.84);
  border-bottom: 1px solid rgba(203, 217, 234, 0.58);
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  color: #5f7896;
  font-size: 11px;
  font-weight: 800;
}

.task-raw-step-toggle:hover {
  background: rgba(230, 240, 255, 0.94);
}

.task-raw-step-toggle small {
  color: #7c90a8;
  font-size: 11px;
  font-weight: 700;
}

.task-raw-step-item {
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(207, 220, 236, 0.72);
}

@keyframes executionPulse {
  0% {
    box-shadow:
      0 0 0 0 rgba(77, 141, 255, 0.26),
      0 0 0 0 rgba(77, 141, 255, 0.12);
  }
  70% {
    box-shadow:
      0 0 0 8px rgba(77, 141, 255, 0),
      0 0 0 16px rgba(77, 141, 255, 0);
  }
  100% {
    box-shadow:
      0 0 0 0 rgba(77, 141, 255, 0),
      0 0 0 0 rgba(77, 141, 255, 0);
  }
}

@keyframes taskSuccessGlow {
  0% {
    transform: translateY(0);
    box-shadow:
      0 0 0 0 rgba(34, 197, 94, 0),
      inset 0 0 0 1px rgba(34, 197, 94, 0);
  }
  40% {
    transform: translateY(-1px);
    box-shadow:
      0 12px 24px rgba(109, 201, 139, 0.16),
      inset 0 0 0 1px rgba(34, 197, 94, 0.2);
  }
  100% {
    transform: translateY(0);
    box-shadow:
      0 0 0 0 rgba(34, 197, 94, 0),
      inset 0 0 0 1px rgba(34, 197, 94, 0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .execution-task-item,
  .execution-task-item.current {
    transition: none;
  }

  .execution-task-item.status-success {
    animation: none;
  }

  .execution-task-item.current::after {
    animation: none;
  }
}

.agent-step-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.5);
  content-visibility: auto;
  contain-intrinsic-size: 120px;
}

.agent-step-title-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.agent-step-title-block strong {
  color: #24364d;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.agent-step-subtitle,
.agent-step-reason,
.agent-step-summary {
  color: #7a8ca4;
  font-size: 11px;
  line-height: 1.5;
}

.agent-step-summary {
  color: #4b6078;
  font-size: 12px;
}

.agent-step-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
  content-visibility: auto;
  contain-intrinsic-size: 120px;
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

.run-detail-loading {
  padding: 8px 4px 18px;
}

.run-detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.run-detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.run-detail-meta-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(247, 250, 255, 0.96);
  border: 1px solid rgba(209, 221, 235, 0.7);
}

.run-detail-label,
.run-detail-section-title {
  color: #7a8ca4;
  font-size: 11px;
  font-weight: 700;
}

.run-detail-code {
  font-size: 12px;
  color: #31465f;
  word-break: break-all;
}

.run-detail-code-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.mini-copy-btn {
  border: 0;
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(228, 243, 255, 0.92);
  color: #4f85c1;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}

.mini-copy-btn:hover {
  background: rgba(217, 237, 255, 0.98);
}

.run-detail-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.run-detail-text,
.run-detail-answer {
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(247, 250, 255, 0.96);
  border: 1px solid rgba(209, 221, 235, 0.7);
  color: #31465f;
  line-height: 1.75;
}

.run-detail-steps-header {
  border: 1px solid rgba(194, 211, 228, 0.4);
  border-bottom: 0;
  border-radius: 14px 14px 0 0;
  background: rgba(255, 255, 255, 0.9);
}

.run-detail-step-list {
  border: 1px solid rgba(194, 211, 228, 0.4);
  border-top: 0;
  border-radius: 0 0 14px 14px;
  background: rgba(255, 255, 255, 0.9);
}

.run-detail-current-task {
  border-radius: 14px;
  border: 1px solid rgba(194, 211, 228, 0.4);
}

.run-detail-progress-strip {
  border: 1px solid rgba(194, 211, 228, 0.4);
  border-top: 0;
}

.run-detail-task-list {
  border-radius: 14px;
  border: 1px solid rgba(194, 211, 228, 0.4);
  background: rgba(255, 255, 255, 0.9);
}

.run-detail-arguments {
  margin-top: 2px;
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

.scroll-to-latest-btn {
  position: sticky;
  left: calc(100% - 148px);
  bottom: 80px;
  z-index: 3;
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 104px;
  padding: 10px 14px;
  border: 1px solid rgba(91, 108, 255, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  color: #46658a;
  font-size: 12px;
  font-weight: 800;
  box-shadow: 0 12px 28px rgba(157, 178, 203, 0.18);
  backdrop-filter: blur(14px);
}

.scroll-to-latest-btn:hover {
  background: #ffffff;
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

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(6px);
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

  .scroll-to-latest-btn {
    bottom: 88px;
  }

  .message-stack {
    max-width: calc(100% - 54px);
  }

  .quick-prompts {
    flex-direction: column;
    width: 100%;
    max-width: 420px;
  }

  .prompt-chip {
    width: 100%;
  }

  .reference-top,
  .setting-inline {
    flex-direction: column;
    align-items: flex-start;
  }

  .run-detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
