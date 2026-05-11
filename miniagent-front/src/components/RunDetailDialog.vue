<template>
  <el-dialog
    v-model="visible"
    title="运行详情"
    width="760px"
    class="run-detail-dialog"
    destroy-on-close
  >
    <div v-if="loading" class="run-detail-loading">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else-if="detail" class="run-detail-body">
      <div class="run-detail-meta">
        <div class="run-detail-meta-row">
          <span class="run-detail-label">Run ID</span>
          <div class="run-detail-code-row">
            <code class="run-detail-code">{{ detail.runId }}</code>
            <button type="button" class="mini-copy-btn" @click="copyRunId(detail.runId)">复制</button>
          </div>
        </div>
        <div class="run-detail-meta-row">
          <span class="run-detail-label">状态</span>
          <span class="tool-badge" :class="getToolStatusClass(detail.status)">
            {{ formatToolStatus(detail.status) }}
          </span>
        </div>
        <div class="run-detail-meta-row">
          <span class="run-detail-label">会话</span>
          <span>{{ detail.sessionId }}</span>
        </div>
        <div class="run-detail-meta-row">
          <span class="run-detail-label">知识库</span>
          <span>{{ detail.kbId }}</span>
        </div>
      </div>

      <div class="run-detail-section">
        <span class="run-detail-section-title">用户目标</span>
        <div class="run-detail-text">{{ detail.userGoal || '—' }}</div>
      </div>

      <div class="run-detail-section">
        <div class="agent-step-header run-detail-steps-header">
          <div class="execution-bubble-meta-left">
            <span>任务清单</span>
          </div>
          <div class="execution-bubble-meta-right">
            <small>{{ getExecutionProgressMeta(detail).summary }}</small>
            <button
              type="button"
              class="execution-collapse-btn"
              @click="toggleExecutionPanel(detail)"
            >
              {{ isExecutionPanelExpanded(detail) ? '收起' : '展开' }}
            </button>
          </div>
        </div>
        <template v-if="isExecutionPanelExpanded(detail)">
        <div class="execution-progress-strip run-detail-progress-strip">
          <div class="execution-progress-copy">
            <strong>{{ getExecutionProgressMeta(detail).headline }}</strong>
            <small>{{ getExecutionProgressMeta(detail).subline }}</small>
          </div>
          <div class="execution-progress-bar">
            <span class="execution-progress-bar-fill" :style="{ width: `${getExecutionProgressMeta(detail).ratio}%` }"></span>
          </div>
        </div>
        <div class="execution-current-task run-detail-current-task" v-if="getCurrentExecutionTask(detail)">
          <span class="execution-current-label">当前焦点</span>
          <strong>{{ getCurrentExecutionTask(detail).title }}</strong>
          <small>{{ getCurrentExecutionTask(detail).subtitle }}</small>
        </div>
        <div class="execution-task-list run-detail-task-list">
          <div
            v-for="task in buildExecutionTasks(detail)"
            :key="task.key"
            class="execution-task-item"
            :class="[
              `status-${String(task.status || '').toLowerCase()}`,
              { current: isCurrentExecutionTask(detail, task) }
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
                  <div v-for="(toolCall, toolIndex) in task.toolCalls" :key="`${task.key}-detail-tool-${toolIndex}`" class="task-tool-tree-item">
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
                  @click="toggleTaskRawSteps(detail, task.key)"
                >
                  <span>{{ isTaskRawStepsExpanded(detail, task.key) ? '收起原始步骤' : '查看原始步骤' }}</span>
                  <small>{{ task.rawSteps.length }} 条</small>
                </button>
                <div v-if="isTaskRawStepsExpanded(detail, task.key)" class="task-raw-step-list">
                  <div
                    v-for="(step, stepIndex) in task.rawSteps"
                    :key="`${task.key}-detail-raw-${step.stepIndex || stepIndex}`"
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
        </template>
      </div>

      <div class="run-detail-section">
        <div class="agent-step-header run-detail-steps-header">
          <span>原始执行步骤</span>
          <small>{{ detail.steps?.length || 0 }} 个步骤</small>
        </div>
        <div class="agent-step-list run-detail-step-list">
          <div
            v-for="(step, stepIndex) in (detail.steps || [])"
            :key="`${step.stepIndex || stepIndex}-${step.stepType || 'step'}`"
            class="agent-step-item"
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
            <pre
              v-if="step.arguments && Object.keys(step.arguments).length"
              class="tool-arguments run-detail-arguments"
            >{{ formatToolArguments(step.arguments) }}</pre>
          </div>
        </div>
      </div>

      <div class="run-detail-section">
        <span class="run-detail-section-title">最终回答</span>
        <div
          class="run-detail-answer"
          v-html="renderMarkdown(detail.finalAnswer || '暂无最终回答')"
        ></div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useExecutionTasks } from '@/composables/useExecutionTasks'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const props = defineProps({
  visible: Boolean,
  loading: Boolean,
  detail: { type: Object, default: null }
})

const emit = defineEmits(['update:visible'])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const {
  buildExecutionTasks,
  getExecutionProgressMeta,
  getCurrentExecutionTask,
  isCurrentExecutionTask,
  isExecutionPanelExpanded,
  toggleExecutionPanel,
  isTaskRawStepsExpanded,
  toggleTaskRawSteps,
  getToolStatusClass,
  formatToolStatus,
  formatToolArguments,
  formatToolHeadline,
  formatToolSubtitle,
  formatAgentStepLabel
} = useExecutionTasks()

const { renderMarkdown } = useMarkdownRenderer()

async function copyRunId(runId) {
  if (!runId) return
  await navigator.clipboard.writeText(runId)
  ElMessage.success('Run ID 已复制')
}
</script>
