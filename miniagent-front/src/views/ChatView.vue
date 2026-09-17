<template>
  <section class="chat-page">
    <div class="chat-shell glass-panel">
      <header class="chat-toolbar">
        <div class="toolbar-left">
          <span class="toolbar-title">
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
            <el-icon><Plus /></el-icon>
            新对话
          </el-button>
        </div>
      </header>

      <Transition name="retrieval-expand">
      <RetrievalSettings
        v-if="showRetrievalSettings"
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
      </Transition>

      <main ref="messagesContainer" class="chat-main">
        <section v-if="!messages.length && !isLoading" class="chat-empty">
          <div class="empty-icon-wrap">
            <div class="empty-icon">问</div>
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
              v-memo="[
                message.content,
                message.role,
                message._isNew,
                message.answerMode,
                message.clarification,
                message.runStatus,
                message.currentActionKey,
                message.completedTaskKeys?.length,
                message.toolCalls?.length,
                message.references?.length,
                message.agentSteps?.length,
                message.agentPlan?.length,
                isStreamingMessage(message),
                thinkingContent,
                thinkingTransition.phase.value,
                thinkingTransition.isTransitioning.value,
                index
              ]"
              class="message-row"
              :class="[message.role, { 'msg-enter': message._isNew }]"
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
                  :class="{ 'fade-out': thinkingTransition.isTransitioning.value }"
                >
                  <div class="thinking-icon">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" stroke-dasharray="4 3"/><path d="M12 6v6l4 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
                  </div>
                  <span>深度思考中<span v-if="thinkingTransition.showEllipsis.value" class="thinking-ellipsis"></span></span>
                </div>

                <div v-if="message.content" class="message-bubble" :class="{ 'is-streaming': isStreamingMessage(message), 'fade-in': thinkingTransition.phase.value === 'answering' && isStreamingMessage(message), 'is-clarify': message.answerMode === 'clarify' }">
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
                  <div
                    v-if="message.approval"
                    class="approval-card"
                    :class="`approval-${String(message.approval.status || '').toLowerCase()}`"
                  >
                    <div class="approval-card-head">
                      <span class="approval-badge">需要授权</span>
                      <strong>{{ message.approval.displayName }}</strong>
                      <small v-if="message.approval.source">{{ message.approval.source }}</small>
                    </div>
                    <p v-if="message.approval.riskSummary" class="approval-risk">
                      {{ message.approval.riskSummary }}
                    </p>
                    <div v-if="hasApprovalArgs(message.approval.arguments)" class="approval-args">
                      <span class="approval-args-label">调用参数</span>
                      <pre>{{ formatApprovalArgs(message.approval.arguments) }}</pre>
                    </div>
                    <div class="approval-card-foot">
                      <template v-if="message.approval.status === 'PENDING' || message.approval.status === 'FAILED'">
                        <small class="approval-hint">
                          <template v-if="message.approval.status === 'FAILED'">
                            {{ message.approval.errorMessage || '提交失败，可重新点击' }}
                          </template>
                          <template v-else-if="message.approval.timeoutSeconds">
                            超过 {{ message.approval.timeoutSeconds }} 秒未确认，将按未授权处理
                          </template>
                        </small>
                        <div class="approval-actions">
                          <button
                            type="button"
                            class="approval-btn approval-btn-reject"
                            :disabled="message.approval.status === 'SUBMITTING'"
                            @click="submitApproval(message, false)"
                          >拒绝执行</button>
                          <button
                            type="button"
                            class="approval-btn approval-btn-approve"
                            :disabled="message.approval.status === 'SUBMITTING'"
                            @click="submitApproval(message, true)"
                          >允许执行</button>
                        </div>
                      </template>
                      <small v-else class="approval-result">{{ approvalStatusText(message.approval.status) }}</small>
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
                    <div v-if="formatRunUsage(message.usage)" class="execution-usage-line">
                      <small>{{ formatRunUsage(message.usage) }}</small>
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

                <ReferencePanel :message="message" />

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
import ReferencePanel from '@/components/ReferencePanel.vue'
import { useExecutionTasks } from '@/composables/useExecutionTasks'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { useStreamingEngine } from '@/composables/useStreamingEngine'
import { useIncrementalMarkdown } from '@/composables/useIncrementalMarkdown'
import { useSmoothScroller } from '@/composables/useSmoothScroller'
import { useThinkingTransition } from '@/composables/useThinkingTransition'
import { removeRecentSession, upsertRecentSession } from '@/utils/chatSessions'
import {
  CopyDocument,
  Operation,
  Plus,
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
const currentAbortController = ref(null)
const showRetrievalSettings = ref(false)
const retrievalOptions = ref(DEFAULT_RETRIEVAL_OPTIONS())
const runDetailDialogVisible = ref(false)
const runDetailLoading = ref(false)
const runDetail = ref(null)

const {
  buildExecutionTasks,
  getExecutionProgressMeta,
  formatRunUsage,
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

const {
  initializeMarkdownRenderer,
  renderMarkdown,
  renderStreamMarkdown,
  resetStreamCache
} = useMarkdownRenderer()

// Streaming engine composables
const streamingEngine = useStreamingEngine({ normalSpeed: 2, fastSpeed: 7, fastThreshold: 100, latencyThreshold: 200 })
const incrementalMd = useIncrementalMarkdown()
const smoothScroller = useSmoothScroller()
const thinkingTransition = useThinkingTransition()

// Expose smooth scroller state for template
const autoScrollEnabled = smoothScroller.autoScrollEnabled
const showScrollToBottom = smoothScroller.showScrollToBottom

let sessionSyncTimer = 0
let scrollStateFrame = 0

function generateSessionId() {
  return `sess_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
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
    runStatus: message.runStatus || ''
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

const APPROVAL_STATUS_TEXT = {
  PENDING: '等待你确认',
  SUBMITTING: '正在提交…',
  APPROVED: '已允许执行',
  REJECTED: '已拒绝执行',
  FAILED: '提交失败，可重新点击'
}

function approvalStatusText(status) {
  return APPROVAL_STATUS_TEXT[status] || ''
}

function hasApprovalArgs(args) {
  return Boolean(args) && typeof args === 'object' && Object.keys(args).length > 0
}

function formatApprovalArgs(args) {
  try {
    return JSON.stringify(args || {}, null, 2)
  } catch (error) {
    return String(args || '')
  }
}

/**
 * 提交一次工具审批决定。
 *
 * 失败时不把状态留在 SUBMITTING，否则按钮会永久失效；回到 PENDING 让用户可以重试。
 */
async function submitApproval(message, approved) {
  const approval = message?.approval
  if (!approval || !approval.approvalId || approval.status === 'SUBMITTING') {
    return
  }
  if (approval.status !== 'PENDING' && approval.status !== 'FAILED') {
    return
  }
  approval.status = 'SUBMITTING'
  try {
    await chatApi.decideApproval(approval.approvalId, approved)
    approval.status = approved ? 'APPROVED' : 'REJECTED'
  } catch (error) {
    approval.status = 'FAILED'
    approval.errorMessage = error?.message || '提交失败，请重试'
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
  // 跳过 / 未解决分开存：前者是模型的正当取舍（不应显示为未完成），后者才是真欠账
  if (Array.isArray(payload.skippedTaskKeys)) {
    message.skippedTaskKeys = payload.skippedTaskKeys.filter((item) => item && typeof item === 'object' && item.key)
  }
  if (Array.isArray(payload.unresolvedTaskKeys)) {
    message.unresolvedTaskKeys = payload.unresolvedTaskKeys.filter((item) => typeof item === 'string' && item)
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
  streamingEngine.flush()
  if (streamingMessage.value) {
    streamingMessage.value.content = streamingEngine.displayedContent.value
  }
}

function appendStreamDelta(delta) {
  if (!delta) return
  streamingEngine.appendChunk(delta)
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
  try {
    const response = await kbApi.list()
    knowledgeBases.value = response.data || []
  } catch {
    knowledgeBases.value = []
  }
}

async function fetchSkills() {
  try {
    const response = await skillApi.list()
    availableSkills.value = response.data || []
    const validSkills = new Set(availableSkills.value.map((item) => item.name))
    selectedSkillNames.value = selectedSkillNames.value.filter((name) => validSkills.has(name))
  } catch {
    availableSkills.value = []
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

const thinkingContent = thinkingTransition.thinkingContent

function createAssistantMessage() {
  return {
    kbId: selectedKbId.value,
    role: 'assistant',
    content: '',
    answerMode: '',
    clarification: null,
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
    _isNew: true
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
      case 'clarify':
        handlers.onClarify?.(payload)
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
      case 'approval_required':
        handlers.onApprovalRequired?.(payload)
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
    createdAt: new Date().toISOString(),
    _isNew: true
  })
  syncSessionSummary()
  await scrollToBottom(true)

  messages.value.push(createAssistantMessage())
  const assistantMessage = messages.value[messages.value.length - 1]
  streamingMessage.value = assistantMessage
  resetStreamCache()
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
        if (thinkingTransition.phase.value === 'idle') {
          thinkingTransition.startThinking()
        }
        thinkingTransition.appendThinkingChunk(delta)
      },
      onMessage(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        const delta = typeof streamPayload === 'string'
          ? streamPayload
          : (streamPayload?.delta || '')
        // Transition from thinking to answering on first message chunk
        if (thinkingTransition.phase.value === 'thinking') {
          thinkingTransition.transitionToAnswer()
        }
        // Start streaming engine if not already started
        if (!streamingEngine.isStreaming.value) {
          streamingEngine.start()
        }
        appendStreamDelta(delta)
      },
      onClarify(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        const clarification = typeof streamPayload === 'string'
          ? streamPayload
          : (streamPayload?.clarification || '')
        if (!clarification) return
        assistantMessage.answerMode = 'clarify'
        assistantMessage.clarification = clarification
        if (thinkingTransition.phase.value === 'thinking') {
          thinkingTransition.transitionToAnswer()
        }
        if (!streamingEngine.isStreaming.value) {
          streamingEngine.start()
        }
        appendStreamDelta(clarification)
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
      onApprovalRequired(streamPayload) {
        hasReceivedStreamEvent = true
        resetStreamTimer()
        // 待确认项挂在消息上由模板渲染成卡片。此刻服务端正阻塞等待结论，
        // 用户点了按钮才会继续——所以这张卡片必须显眼且不可折叠掉。
        assistantMessage.approval = {
          approvalId: streamPayload?.approvalId || '',
          toolName: streamPayload?.toolName || '',
          displayName: streamPayload?.displayName || streamPayload?.toolName || '未知工具',
          source: streamPayload?.source || '',
          riskSummary: streamPayload?.riskSummary || '',
          arguments: streamPayload?.arguments || {},
          timeoutSeconds: Number(streamPayload?.timeoutSeconds) || 0,
          status: 'PENDING'
        }
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
        if (typeof streamPayload?.answerMode === 'string') {
          assistantMessage.answerMode = streamPayload.answerMode
        }
        if (typeof streamPayload?.clarification === 'string') {
          assistantMessage.clarification = streamPayload.clarification
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
        // 成本与耗时：落库同一份数据，前端直接展示（耗时 / token / 缓存命中）
        if (streamPayload?.usage && typeof streamPayload.usage === 'object') {
          assistantMessage.usage = streamPayload.usage
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
    // Flush streaming engine and finalize
    flushStreamDelta()
    if (streamingMessage.value) {
      streamingMessage.value.content = streamingEngine.displayedContent.value
    }
    streamingEngine.reset()
    thinkingTransition.reset()
    if (streamTimer) {
      window.clearTimeout(streamTimer)
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
  if (force) {
    smoothScroller.scrollToBottom()
  } else {
    smoothScroller.onContentUpdated()
  }
}

async function copyMessage(content) {
  await navigator.clipboard.writeText(content)
  ElMessage.success('已复制到剪贴板')
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

// Single watcher: sync stream content + trigger scroll together.
// Merging avoids a second reactive trigger — previously the content write
// fired a separate watcher just to call onContentUpdated, which already
// throttles internally (100ms).
watch(
  () => streamingEngine.displayedContent.value,
  (newContent) => {
    if (!streamingMessage.value || !newContent) {
      return
    }
    streamingMessage.value.content = newContent
    smoothScroller.onContentUpdated()
  },
  { flush: 'post' }
)

onMounted(async () => {
  // Bind smooth scroller to messages container
  if (messagesContainer.value) {
    smoothScroller.bindContainer(messagesContainer.value)
  }
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
  smoothScroller.unbind()
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
  /* 透明：统一透出 app 层的宣纸底 + 淡墨晕染，不再自带冷灰渐变补丁 */
  background: transparent;
}

.chat-shell {
  height: 100%;
  min-height: 0;
  padding: 16px;
  border-radius: var(--radius-xl);
  position: relative;
  display: grid;
  /* 四行：工具栏 / 检索设置（可折叠） / 消息区 / 输入区（文档流，替代原 absolute 悬浮） */
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 14px;
  overflow: hidden;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  box-shadow: var(--shadow-sm);
}

.chat-toolbar {
  grid-row: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  row-gap: 8px;
  gap: 12px;
  padding: 2px 6px 0;
  /* 无框信息条：不再框中套框，与下方内容以留白分隔 */
  background: transparent;
}

.toolbar-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  white-space: nowrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-desc {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.chip-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary-color);
}

.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border-radius: 999px;
  background: var(--bg-surface-dark);
  border: 1px solid var(--border-light);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.03);
}

.mode-chip {
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.mode-chip:hover {
  color: var(--text-primary);
}

.mode-chip.active {
  background: var(--bg-surface-strong);
  color: var(--primary-strong);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06), 0 0 0 1px rgba(166, 61, 42, 0.16);
  font-weight: 700;
}

.kb-select {
  width: 240px;
}

.ghost-btn {
  min-width: 100px;
}

.chat-header-actions :deep(.el-select .el-input__wrapper),
.chat-header-actions :deep(.el-select__wrapper) {
  background: rgba(251, 249, 243, 0.96) !important;
  border-radius: 999px !important;
  box-shadow: 0 0 0 1px var(--border-light) inset !important;
  padding: 0 14px !important;
  height: 32px !important;
  min-height: 32px !important;
}

.chat-header-actions :deep(.el-select__placeholder),
.chat-header-actions :deep(.el-select__selected-item) {
  color: var(--text-secondary) !important;
  font-size: var(--text-sm) !important;
}

.chat-header-actions :deep(.el-select__placeholder),
.chat-header-actions :deep(.el-select__selected-item),
.chat-header-actions :deep(.el-input__inner),
.chat-header-actions :deep(.el-select__caret),
.chat-header-actions :deep(.el-input__icon) {
  color: var(--text-secondary) !important;
  font-size: 12.5px !important;
}

.chat-header-actions :deep(.el-button) {
  background: rgba(255, 255, 255, 0.92) !important;
  border-color: var(--border-light) !important;
  border-radius: 999px !important;
  color: var(--text-secondary) !important;
  font-size: var(--text-sm) !important;
  height: 32px !important;
  padding: 0 14px !important;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04) !important;
  transition: all var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)) !important;
}

.chat-header-actions :deep(.el-button:hover) {
  transform: scale(1.02);
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08) !important;
  background: #ffffff !important;
  color: var(--primary-strong) !important;
  border-color: rgba(166, 61, 42, 0.28) !important;
}

.chat-header-actions :deep(.el-button span),
.chat-header-actions :deep(.el-button .el-icon) {
  color: var(--text-secondary) !important;
}

.chat-header-actions :deep(.el-button:hover span),
.chat-header-actions :deep(.el-button:hover .el-icon) {
  color: var(--primary-strong) !important;
}

.retrieval-panel {
  grid-row: 2;
  padding: 18px 20px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(248, 250, 252, 0.94));
  border: 1px solid var(--border-light);
  box-shadow: 0 4px 16px rgba(24, 24, 27, 0.05);
  overflow: hidden;
  transform-origin: top center;
}

/* Elastic expand/collapse transition for settings panel */
.retrieval-expand-enter-active {
  transition: transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)),
              opacity var(--duration-jelly, 400ms) ease;
}

.retrieval-expand-leave-active {
  transition: transform 250ms ease-in,
              opacity 200ms ease-in;
}

.retrieval-expand-enter-from {
  transform: scaleY(0.6) scaleX(0.98);
  opacity: 0;
}

.retrieval-expand-enter-to {
  transform: scaleY(1) scaleX(1);
  opacity: 1;
}

.retrieval-expand-leave-from {
  transform: scaleY(1) scaleX(1);
  opacity: 1;
}

.retrieval-expand-leave-to {
  transform: scaleY(0.6) scaleX(0.98);
  opacity: 0;
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
  color: var(--text-primary);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-setting-tip {
  margin-top: 8px;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.setting-field-full {
  grid-column: 1 / -1;
}

.setting-label {
  color: var(--text-muted);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
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
  grid-row: 3;
  min-height: 0;
  overflow: auto;
  /* 输入区已回归文档流（grid 第四行）；背景透明，与 shell 同一张纸，消除米色补丁 */
  padding: 4px 8px 16px;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  contain: layout paint;
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
  /* 墨方印：与侧栏品牌标同语言 */
  width: 56px;
  height: 56px;
  border-radius: var(--radius-md);
  display: grid;
  place-items: center;
  color: var(--text-inverse, #f9f4ea);
  background: var(--text-primary, #2e2a23);
  font-family: var(--font-serif, serif);
  font-size: 26px;
  font-weight: var(--weight-semibold, 600);
  box-shadow: inset 0 0 0 2px rgba(249, 244, 234, 0.28), var(--shadow-sm);
}

.chat-empty h2 {
  font-family: var(--font-serif, serif);
  font-size: var(--text-2xl, 26px);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0.02em;
  line-height: 1.2;
  margin-bottom: 10px;
  color: var(--text-primary);
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
  border: 1px solid var(--border-light);
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.prompt-chip:hover {
  color: var(--text-primary);
  border-color: rgba(166, 61, 42, 0.28);
  background: rgba(166, 61, 42, 0.04);
  transform: scale(var(--jelly-hover-scale, 1.03));
}

.prompt-chip:active {
  transform: scaleX(var(--jelly-press-x, 1.04)) scaleY(var(--jelly-press-y, 0.96));
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

/* Message entrance animations */
.message-row.assistant.msg-enter {
  animation: message-enter-left var(--duration-entrance, 380ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)) both;
}

.message-row.user.msg-enter {
  animation: message-enter-right var(--duration-entrance, 380ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)) both;
}

@media (prefers-reduced-motion: reduce) {
  .message-row.msg-enter {
    animation: none !important;
  }
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
  background: linear-gradient(135deg, #a63d2a, #8c3322);
  color: #ffffff;
  box-shadow:
    0 12px 24px rgba(166, 61, 42, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.user-avatar {
  background: linear-gradient(135deg, rgba(166, 61, 42, 0.14), rgba(166, 61, 42, 0.06));
  color: var(--primary-strong);
  box-shadow:
    0 10px 20px rgba(166, 61, 42, 0.12),
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
  border: 1px solid var(--border-light);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 250, 252, 0.98)),
    radial-gradient(circle at top left, rgba(166, 61, 42, 0.08), transparent 38%);
  box-shadow:
    0 18px 36px rgba(24, 24, 27, 0.06),
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
  background: linear-gradient(180deg, rgba(244, 244, 245, 0.84), rgba(248, 250, 252, 0.76));
  border-bottom: 1px solid var(--border-light);
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
  background: rgba(244, 244, 245, 0.92);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
}

.execution-collapse-btn:hover {
  background: rgba(166, 61, 42, 0.08);
  color: var(--primary-strong);
}

.execution-bubble-kicker {
  display: inline-flex;
  align-items: center;
  padding: 4px 9px;
  border-radius: 999px;
  background: rgba(166, 61, 42, 0.12);
  color: var(--primary-strong);
  font-size: var(--text-sm);
  font-weight: 600;
}

.execution-bubble-meta small {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.message-stack.user {
  align-items: flex-end;
  max-width: min(560px, calc(100% - 54px));
}

.message-meta {
  display: flex;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  padding-inline: 4px;
}

.message-bubble {
  position: relative;
  /* 平面卡片：层级靠发丝边框，不靠投影堆叠（文档 §5.1） */
  padding: 14px 18px;
  border-radius: var(--radius-md);
  line-height: 1.75;
  font-size: var(--text-md);
  letter-spacing: 0;
  border: 1px solid var(--border-light);
  box-shadow: var(--shadow-xs);
  transition:
    box-shadow var(--transition-fast),
    border-color var(--transition-fast);
}

.message-row:hover .message-bubble {
  border-color: var(--border-2, rgba(72, 60, 42, 0.22));
}

.message-bubble.is-streaming {
  border-color: var(--border-medium);
  box-shadow: var(--shadow-glow-soft);
}

.message-bubble.is-clarify {
  background: linear-gradient(135deg, rgba(255, 244, 224, 0.92), rgba(255, 250, 235, 0.88));
  border-left: 3px solid rgba(162, 115, 44, 0.55);
}

.message-bubble.is-clarify::before {
  background: linear-gradient(135deg, rgba(162, 115, 44, 0.3), rgba(255, 255, 255, 0.2) 60%, rgba(162, 115, 44, 0.15));
}

.message-stream-md {
  word-break: break-word;
}

.message-stream-md :deep(p) {
  margin: 0;
}

/* 正文行内标记（来源标注、参数与状态符）：统一为"朱砂小签"质感，
   避免浏览器默认等宽样式把回答挤成一片乱麻 */
.message-bubble :deep(:not(pre) > code) {
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 0.84em;
  padding: 0.1em 0.5em;
  margin: 0 1px;
  border-radius: 5px;
  color: var(--accent-color, #a63d2a);
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent-color, #a63d2a) 20%, transparent);
  word-break: break-word;
}

.message-bubble :deep(strong) {
  font-weight: 650;
  color: var(--text-primary);
}

/* ── Markdown 正文排版：标题成节、列表成行，拒绝一堵字墙 ────────── */
.message-bubble :deep(p) {
  margin: 0 0 10px;
  line-height: 1.78;
}
.message-bubble :deep(p:last-child) {
  margin-bottom: 0;
}

.message-bubble :deep(h1),
.message-bubble :deep(h2),
.message-bubble :deep(h3),
.message-bubble :deep(h4) {
  margin: 18px 0 8px;
  font-weight: 650;
  color: var(--text-primary);
  line-height: 1.4;
  padding-left: 9px;
  border-left: 3px solid color-mix(in srgb, var(--accent-color, #a63d2a) 55%, transparent);
}
.message-bubble :deep(h1) { font-size: 1.25em; }
.message-bubble :deep(h2) { font-size: 1.14em; }
.message-bubble :deep(h3) { font-size: 1.05em; }
.message-bubble :deep(h4) { font-size: 1em; }
.message-bubble :deep(h1:first-child),
.message-bubble :deep(h2:first-child),
.message-bubble :deep(h3:first-child) {
  margin-top: 2px;
}

.message-bubble :deep(ul),
.message-bubble :deep(ol) {
  margin: 4px 0 12px;
  padding-left: 1.4em;
}
.message-bubble :deep(li) {
  margin: 5px 0;
  line-height: 1.7;
}
.message-bubble :deep(li::marker) {
  color: var(--accent-color, #a63d2a);
}
.message-bubble :deep(li > p) {
  margin: 0;
}

.message-bubble :deep(blockquote) {
  margin: 8px 0;
  padding: 6px 12px;
  border-left: 3px solid color-mix(in srgb, var(--accent-color, #a63d2a) 45%, transparent);
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 5%, transparent);
  border-radius: 0 6px 6px 0;
  color: var(--text-secondary, #888);
}
.message-bubble :deep(blockquote p) {
  margin: 0;
}

.message-bubble :deep(hr) {
  border: none;
  border-top: 1px dashed color-mix(in srgb, var(--accent-color, #a63d2a) 28%, transparent);
  margin: 14px 0;
}

.message-bubble :deep(table) {
  width: 100%;
  margin: 8px 0 12px;
  border-collapse: collapse;
  font-size: 0.92em;
}
.message-bubble :deep(th),
.message-bubble :deep(td) {
  border: 1px solid color-mix(in srgb, var(--accent-color, #a63d2a) 14%, transparent);
  padding: 5px 10px;
  text-align: left;
}
.message-bubble :deep(th) {
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 7%, transparent);
  font-weight: 650;
}

.stream-cursor {
  display: inline-block;
  width: 2.5px;
  height: 1.1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--primary-color);
  border-radius: 2px;
  animation: cursorBlink 1s ease-in-out infinite;
}

.stream-cursor.fade-out {
  animation: cursorFadeOut 0.3s ease-out forwards;
}

.thinking-indicator {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 16px;
  background: rgba(166, 61, 42, 0.08);
  border: 1px solid rgba(166, 61, 42, 0.22);
  color: var(--primary-strong);
  font-size: 13px;
  font-weight: 600;
  animation: thinkingPulse 2s ease-in-out infinite;
  transition: opacity 0.2s ease-out;
}

.thinking-indicator.fade-out {
  opacity: 0;
  transition: opacity 0.2s ease-out;
}

.message-bubble.fade-in {
  animation: answerFadeIn 0.15s ease-in forwards;
}

.thinking-ellipsis::after {
  content: '';
  animation: ellipsis 1.5s steps(3, end) infinite;
}

@keyframes answerFadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes ellipsis {
  0% { content: ''; }
  33% { content: '.'; }
  66% { content: '..'; }
  100% { content: '...'; }
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
  background: var(--bg-surface-strong);
  color: var(--text-primary);
  border-color: var(--border-light);
  border-top-left-radius: var(--radius-xs);
}

.message-stack.user .message-bubble {
  background: var(--primary-soft);
  color: var(--text-primary);
  border-color: var(--border-medium);
  border-top-right-radius: var(--radius-xs);
  font-weight: var(--weight-medium);
  box-shadow: none;
}

.message-bubble :deep(pre) {
  overflow: auto;
  padding: 16px 18px;
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(252, 254, 252, 0.99), rgba(245, 249, 246, 0.99)),
    linear-gradient(135deg, rgba(166, 61, 42, 0.06), transparent 44%);
  border: 1px solid var(--border-light);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.72),
    0 12px 28px rgba(24, 24, 27, 0.06);
  margin: 12px 0;
  color: var(--text-primary);
}

.message-bubble :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  font-variant-ligatures: common-ligatures;
}

.message-bubble :deep(pre code),
.message-bubble :deep(.hljs) {
  background: transparent;
  color: var(--text-primary);
}

.message-bubble :deep(.hljs-comment),
.message-bubble :deep(.hljs-quote) {
  color: var(--text-muted);
}

.message-bubble :deep(.hljs-keyword),
.message-bubble :deep(.hljs-selector-tag),
.message-bubble :deep(.hljs-built_in),
.message-bubble :deep(.hljs-name),
.message-bubble :deep(.hljs-tag) {
  color: #7c3aed;
}

.message-bubble :deep(.hljs-string),
.message-bubble :deep(.hljs-attr),
.message-bubble :deep(.hljs-template-tag),
.message-bubble :deep(.hljs-template-variable) {
  color: #8c3322;
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
  color: var(--text-primary);
}

.message-bubble :deep(:not(pre) > code) {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  background: rgba(166, 61, 42, 0.08);
  border: 1px solid rgba(166, 61, 42, 0.18);
  color: var(--primary-strong);
  font-size: 12px;
  line-height: 1.5;
  vertical-align: baseline;
}

.message-bubble :deep(p) {
  margin: 0 0 10px;
  line-height: 1.75;
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
  border-left: 5px solid var(--primary-color);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(252, 254, 252, 0.98));
  border-top: 1px solid var(--border-light);
  border-right: 1px solid var(--border-light);
  border-bottom: 1px solid var(--border-light);
  border-radius: 0 16px 16px 0;
  color: var(--text-secondary);
}

.message-bubble :deep(h1),
.message-bubble :deep(h2),
.message-bubble :deep(h3),
.message-bubble :deep(h4) {
  margin: 18px 0 10px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--text-primary);
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
  background: linear-gradient(90deg, rgba(161, 161, 170, 0), rgba(113, 113, 122, 0.5), rgba(161, 161, 170, 0));
}

.message-bubble :deep(a) {
  color: var(--primary-strong);
  text-decoration: none;
  border-bottom: 1px solid rgba(166, 61, 42, 0.3);
  padding-bottom: 1px;
  transition: color var(--transition-fast), border-color var(--transition-fast), background var(--transition-fast);
}

.message-bubble :deep(a:hover) {
  color: var(--primary-color);
  border-color: rgba(166, 61, 42, 0.5);
}

.message-bubble :deep(a[href^="/"]) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 10px;
  background: rgba(166, 61, 42, 0.08);
  border: 1px solid rgba(166, 61, 42, 0.22);
  color: var(--primary-strong);
  font-weight: 700;
}

.message-bubble :deep(a[href^="/"]:hover) {
  background: rgba(166, 61, 42, 0.14);
}

.message-bubble :deep(table) {
  border-collapse: separate;
  border-spacing: 0;
  width: 100%;
  margin: 14px 0;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid var(--border-light);
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 8px 22px rgba(24, 24, 27, 0.04);
}

.message-bubble :deep(th),
.message-bubble :deep(td) {
  padding: 12px 14px;
  border-right: 1px solid var(--border-light);
  border-bottom: 1px solid var(--border-light);
  text-align: left;
  vertical-align: top;
  line-height: 1.7;
}

.message-bubble :deep(th) {
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.98), rgba(244, 244, 245, 0.98));
  font-weight: 600;
  color: var(--text-primary);
  border-bottom: 1px solid var(--border-light);
}

.message-bubble :deep(tr:last-child td) {
  border-bottom: 0;
}

.message-bubble :deep(th:last-child),
.message-bubble :deep(td:last-child) {
  border-right: 0;
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
  background: rgba(166, 61, 42, 0.12);
  color: var(--primary-strong);
  font-size: var(--text-sm);
  font-weight: 700;
}

.retrieval-summary-text {
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-word;
}


.tool-panel {
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.86), rgba(248, 250, 252, 0.94));
  border: 1px solid var(--border-light);
  overflow: hidden;
}

/* ---- 工具审批卡片：服务端正阻塞等待结论，因此必须显眼且不受折叠影响 ---- */
.approval-card {
  margin: 10px 0 12px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(245, 158, 11, 0.45);
  background: linear-gradient(180deg, rgba(255, 251, 235, 0.96), rgba(255, 247, 237, 0.92));
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.approval-card.approval-approved {
  border-color: rgba(34, 197, 94, 0.4);
  background: linear-gradient(180deg, rgba(240, 253, 244, 0.95), rgba(236, 253, 245, 0.9));
}

.approval-card.approval-rejected,
.approval-card.approval-failed {
  border-color: rgba(148, 163, 184, 0.5);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.95), rgba(241, 245, 249, 0.9));
}

.approval-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.approval-card-head strong {
  font-size: 13px;
  color: #1f2937;
  word-break: break-all;
}

.approval-card-head small {
  color: #6b7280;
  font-size: 11px;
}

.approval-badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: #92400e;
  background: rgba(251, 191, 36, 0.28);
}

.approval-risk {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #92400e;
}

.approval-args {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.approval-args-label {
  font-size: 11px;
  color: #6b7280;
}

.approval-args pre {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.06);
  font-size: 11px;
  line-height: 1.5;
  color: #334155;
  overflow-x: auto;
  max-height: 140px;
}

.approval-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.approval-hint {
  color: #92400e;
  font-size: 11px;
}

.approval-result {
  color: #64748b;
  font-size: 11px;
}

.approval-actions {
  display: inline-flex;
  gap: 8px;
  margin-left: auto;
}

.approval-btn {
  padding: 5px 14px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.approval-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.approval-btn-reject {
  background: rgba(255, 255, 255, 0.9);
  border-color: rgba(148, 163, 184, 0.6);
  color: #475569;
}

.approval-btn-reject:hover:not(:disabled) {
  background: #fff;
  border-color: #94a3b8;
}

.approval-btn-approve {
  background: #f59e0b;
  color: #fff;
}

.approval-btn-approve:hover:not(:disabled) {
  background: #d97706;
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
  background: rgba(244, 244, 245, 0.92);
  border: 1px solid var(--border-light);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-step-header {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  font-size: 12px;
  font-weight: 600;
}

.agent-step-header small {
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.execution-progress-strip {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  background: linear-gradient(180deg, rgba(252, 254, 252, 0.95), rgba(248, 250, 252, 0.92));
}

.execution-progress-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.execution-usage-line {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-subtle, rgba(255, 255, 255, 0.08));
}

.execution-usage-line small {
  color: var(--text-tertiary);
  font-size: var(--text-xs, 12px);
  line-height: 1.5;
}

.execution-progress-copy strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.execution-progress-copy small {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.execution-progress-bar {
  position: relative;
  width: 100%;
  height: 8px;
  border-radius: 999px;
  background: rgba(228, 228, 231, 0.62);
  overflow: hidden;
}

.execution-progress-bar-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: linear-gradient(90deg, #a63d2a, #8c3322);
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
  border-bottom: 1px solid var(--border-light);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.92), rgba(252, 254, 252, 0.92));
}

.execution-current-label {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.execution-current-task strong {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.execution-current-task small {
  color: var(--text-secondary);
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
  background: linear-gradient(180deg, rgba(166, 61, 42, 0.5), rgba(228, 228, 231, 0.5));
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
  border: 1px solid var(--border-light);
  transition:
    transform var(--transition-fast),
    box-shadow var(--transition-fast),
    border-color var(--transition-fast),
    background var(--transition-fast);
}

.execution-task-item.status-running {
  border-color: rgba(162, 115, 44, 0.28);
  box-shadow: inset 0 0 0 1px rgba(162, 115, 44, 0.12);
}

.execution-task-item.status-success {
  border-color: rgba(92, 122, 94, 0.2);
  animation: taskSuccessGlow 520ms ease-out;
}

.execution-task-item.status-failed {
  border-color: rgba(166, 61, 42, 0.22);
}

.execution-task-item.status-partial {
  border-color: rgba(162, 115, 44, 0.24);
  background: linear-gradient(180deg, rgba(255, 251, 243, 0.94), rgba(255, 255, 255, 0.82));
}

.execution-task-item.status-unfinished {
  border-style: dashed;
  background: rgba(250, 252, 255, 0.74);
}

.execution-task-item.current {
  transform: translateY(-1px);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(248, 250, 252, 0.96));
  box-shadow:
    0 12px 28px rgba(24, 24, 27, 0.08),
    inset 0 0 0 1px rgba(166, 61, 42, 0.18);
}

.execution-task-item.current::after {
  content: '';
  position: absolute;
  left: 8px;
  top: 50%;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(166, 61, 42, 0.9);
  box-shadow:
    0 0 0 6px rgba(166, 61, 42, 0.12),
    0 0 0 12px rgba(166, 61, 42, 0.06);
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
  background: rgba(166, 61, 42, 0.1);
  color: var(--primary-strong);
  font-size: 12px;
  font-weight: 600;
  position: relative;
  z-index: 1;
  box-shadow: 0 0 0 4px rgba(248, 250, 252, 0.96);
}

.execution-task-item.status-success .execution-task-index {
  background: rgba(92, 122, 94, 0.14);
  color: #4a684c;
}

.execution-task-item.status-running .execution-task-index,
.execution-task-item.current .execution-task-index {
  background: rgba(166, 61, 42, 0.18);
  color: var(--primary-strong);
}

.execution-task-item.status-failed .execution-task-index {
  background: rgba(166, 61, 42, 0.14);
  color: #8c3322;
}

.execution-task-item.status-partial .execution-task-index {
  background: rgba(162, 115, 44, 0.16);
  color: #8a6224;
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
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
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
  background: rgba(166, 61, 42, 0.1);
  color: var(--primary-strong);
  font-size: var(--text-sm);
  font-weight: 600;
  line-height: 1.4;
}

.execution-task-subtitle {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.raw-step-list {
  border-top: 1px dashed var(--border-light);
  padding-top: 10px;
}

.raw-step-list .agent-step-item {
  background: rgba(255, 255, 255, 0.42);
}

.task-tool-tree,
.task-raw-step-block {
  margin-top: 10px;
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.8);
  border: 1px solid var(--border-light);
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
  background: rgba(244, 244, 245, 0.84);
  border-bottom: 1px solid var(--border-light);
}

.task-tool-tree-header span,
.task-tool-tree-header small {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
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
  border: 1px solid var(--border-light);
}

.task-tool-tree-item::before {
  content: '';
  position: absolute;
  left: -7px;
  top: 18px;
  width: 10px;
  height: 2px;
  border-radius: 999px;
  background: rgba(161, 161, 170, 0.6);
}

.task-raw-step-toggle {
  width: 100%;
  border: 0;
  background: rgba(244, 244, 245, 0.84);
  border-bottom: 1px solid var(--border-light);
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
}

.task-raw-step-toggle:hover {
  background: rgba(166, 61, 42, 0.06);
}

.task-raw-step-toggle small {
  color: var(--text-muted);
  font-size: var(--text-sm);
  font-weight: 700;
}

.task-raw-step-item {
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid var(--border-light);
}

@keyframes executionPulse {
  0% {
    box-shadow:
      0 0 0 0 rgba(166, 61, 42, 0.26),
      0 0 0 0 rgba(166, 61, 42, 0.12);
  }
  70% {
    box-shadow:
      0 0 0 8px rgba(166, 61, 42, 0),
      0 0 0 16px rgba(166, 61, 42, 0);
  }
  100% {
    box-shadow:
      0 0 0 0 rgba(166, 61, 42, 0),
      0 0 0 0 rgba(166, 61, 42, 0);
  }
}

@keyframes taskSuccessGlow {
  0% {
    transform: translateY(0);
    box-shadow:
      0 0 0 0 rgba(92, 122, 94, 0),
      inset 0 0 0 1px rgba(92, 122, 94, 0);
  }
  40% {
    transform: translateY(-1px);
    box-shadow:
      0 12px 24px rgba(109, 201, 139, 0.16),
      inset 0 0 0 1px rgba(92, 122, 94, 0.2);
  }
  100% {
    transform: translateY(0);
    box-shadow:
      0 0 0 0 rgba(92, 122, 94, 0),
      inset 0 0 0 1px rgba(92, 122, 94, 0);
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
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.agent-step-subtitle,
.agent-step-reason,
.agent-step-summary {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.agent-step-summary {
  color: var(--text-primary);
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
  border-bottom: 1px solid var(--border-light);
  font-size: 12px;
  font-weight: 600;
}

.tool-header small {
  color: var(--text-muted);
  font-size: var(--text-sm);
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
  background: rgba(255, 255, 255, 0.6);
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
  background: rgba(244, 244, 245, 0.8);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.tool-badge.success {
  background: rgba(92, 122, 94, 0.18);
  color: #4a684c;
}

.tool-badge.warning {
  background: rgba(162, 115, 44, 0.18);
  color: #8a6224;
}

.tool-badge.danger {
  background: rgba(166, 61, 42, 0.18);
  color: #8c3322;
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
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.tool-subtitle {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
  line-height: 1.4;
}

.tool-arguments {
  margin: 0;
  padding: 11px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.98);
  border: 1px solid var(--border-light);
  color: var(--text-primary);
  font-size: var(--text-sm);
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
  background: rgba(248, 250, 252, 0.96);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.action-btn.active {
  color: var(--primary-strong);
  background: rgba(166, 61, 42, 0.12);
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
  background: rgba(248, 250, 252, 0.96);
  border: 1px solid var(--border-light);
}

.run-detail-label,
.run-detail-section-title {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.run-detail-code {
  font-size: 12px;
  color: var(--text-primary);
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
  background: rgba(166, 61, 42, 0.1);
  color: var(--primary-strong);
  font-size: var(--text-sm);
  font-weight: 700;
  cursor: pointer;
}

.mini-copy-btn:hover {
  background: rgba(166, 61, 42, 0.18);
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
  background: rgba(248, 250, 252, 0.96);
  border: 1px solid var(--border-light);
  color: var(--text-primary);
  line-height: 1.75;
}

.run-detail-steps-header {
  border: 1px solid var(--border-light);
  border-bottom: 0;
  border-radius: 14px 14px 0 0;
  background: rgba(255, 255, 255, 0.9);
}

.run-detail-step-list {
  border: 1px solid var(--border-light);
  border-top: 0;
  border-radius: 0 0 14px 14px;
  background: rgba(255, 255, 255, 0.9);
}

.run-detail-current-task {
  border-radius: 14px;
  border: 1px solid var(--border-light);
}

.run-detail-progress-strip {
  border: 1px solid var(--border-light);
  border-top: 0;
}

.run-detail-task-list {
  border-radius: 14px;
  border: 1px solid var(--border-light);
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
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, #a63d2a, #8c3322);
  animation: jelly-bounce 1000ms var(--spring-bounce, cubic-bezier(0.68, -0.55, 0.265, 1.55)) infinite;
  will-change: transform;
}

.loading-bubble span:nth-child(2) {
  animation-delay: 150ms;
}

.loading-bubble span:nth-child(3) {
  animation-delay: 300ms;
}

@media (prefers-reduced-motion: reduce) {
  .loading-bubble span {
    animation: none !important;
  }
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
  border: 1px solid rgba(166, 61, 42, 0.22);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  color: var(--primary-strong);
  font-size: 12px;
  font-weight: 600;
  box-shadow: 0 12px 28px rgba(24, 24, 27, 0.08);
  cursor: pointer;
  transition: transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)), box-shadow var(--transition-fast), background var(--transition-fast);
}

.scroll-to-latest-btn:hover {
  background: #ffffff;
  transform: scale(var(--jelly-hover-scale, 1.03));
  box-shadow: 0 16px 36px rgba(166, 61, 42, 0.16);
}

.scroll-to-latest-btn:active {
  transform: scaleX(var(--jelly-press-x, 1.04)) scaleY(var(--jelly-press-y, 0.96));
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
  50% { opacity: 0.2; }
}

@keyframes cursorFadeOut {
  from { opacity: 1; }
  to { opacity: 0; }
}

@keyframes thinkingPulse {
  0%, 100% { opacity: 0.7; }
  50% { opacity: 1; }
}

@keyframes thinkingSpin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
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

@media (max-width: 1320px) {
  .toolbar-desc {
    display: none;
  }
}

@media (max-width: 900px) {
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

  .setting-inline {
    flex-direction: column;
    align-items: flex-start;
  }

  .run-detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
