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
            <span class="setting-label">Skill</span>
            <el-select
              v-model="selectedSkillNames"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="不启用额外 skill"
            >
              <el-option
                v-for="skill in availableSkills"
                :key="skill.name"
                :label="skill.title || skill.name"
                :value="skill.name"
              >
                <div class="skill-option-row">
                  <span class="skill-option-name">{{ skill.title || skill.name }}</span>
                  <el-tag size="small" :type="skill.executable ? 'success' : 'info'" effect="plain">
                    {{ skill.executable ? '执行器' : 'Prompt' }}
                  </el-tag>
                </div>
              </el-option>
            </el-select>
            <div v-if="availableSkills.length" class="skill-setting-tip">
              当前可选 {{ availableSkills.length }} 个 skill，其中 {{ executableSkillCount }} 个可直接作为执行器使用。</div>
          </div>

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
            <span class="setting-label">文档名称关键词</span>
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

      <footer class="chat-input-wrap">
        <div class="chat-input-card glass-panel">
          <div class="chat-input-meta">
            <div class="chat-input-hints">
              <span class="hint-badge">RAG 检索增强</span>
              <span class="hint-badge">{{ currentKnowledgeBaseName || '等待选择知识库' }}</span>
              <span v-if="selectedSkillNames.length" class="hint-badge">
                Skill {{ selectedSkillNames.length }} / 执行器 {{ selectedExecutableSkillCount }}
              </span>
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

            <div class="composer-actions">
              <transition name="fade-slide" mode="out-in">
                <el-button
                  v-if="isLoading"
                  key="stop"
                  class="send-btn send-btn-inside stop-btn"
                  @click="stopGenerating"
                >
                  <el-icon><CloseBold /></el-icon>
                  停止生成
                </el-button>
                <el-button
                  v-else
                  key="send"
                  type="primary"
                  class="send-btn send-btn-inside"
                  :disabled="!question.trim() || !selectedKbId"
                  @click="sendMessage"
                >
                  <el-icon><Promotion /></el-icon>
                  发送消息
                </el-button>
              </transition>
            </div>
          </div>
        </div>
      </footer>
    </div>

    <el-dialog
      v-model="runDetailDialogVisible"
      title="运行详情"
      width="760px"
      class="run-detail-dialog"
      destroy-on-close
    >
      <div v-if="runDetailLoading" class="run-detail-loading">
        <el-skeleton :rows="6" animated />
      </div>

      <div v-else-if="runDetail" class="run-detail-body">
        <div class="run-detail-meta">
          <div class="run-detail-meta-row">
            <span class="run-detail-label">Run ID</span>
            <div class="run-detail-code-row">
              <code class="run-detail-code">{{ runDetail.runId }}</code>
              <button type="button" class="mini-copy-btn" @click="copyRunId(runDetail.runId)">复制</button>
            </div>
          </div>
          <div class="run-detail-meta-row">
            <span class="run-detail-label">状态</span>
            <span class="tool-badge" :class="getToolStatusClass(runDetail.status)">
              {{ formatToolStatus(runDetail.status) }}
            </span>
          </div>
          <div class="run-detail-meta-row">
            <span class="run-detail-label">会话</span>
            <span>{{ runDetail.sessionId }}</span>
          </div>
          <div class="run-detail-meta-row">
            <span class="run-detail-label">知识库</span>
            <span>{{ runDetail.kbId }}</span>
          </div>
        </div>

        <div class="run-detail-section">
          <span class="run-detail-section-title">用户目标</span>
          <div class="run-detail-text">{{ runDetail.userGoal || '—' }}</div>
        </div>

        <div class="run-detail-section">
          <div class="agent-step-header run-detail-steps-header">
            <div class="execution-bubble-meta-left">
              <span>任务清单</span>
            </div>
            <div class="execution-bubble-meta-right">
              <small>{{ getExecutionProgressMeta(runDetail).summary }}</small>
              <button
                type="button"
                class="execution-collapse-btn"
                @click="toggleExecutionPanel(runDetail)"
              >
                {{ isExecutionPanelExpanded(runDetail) ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <template v-if="isExecutionPanelExpanded(runDetail)">
          <div class="execution-progress-strip run-detail-progress-strip">
            <div class="execution-progress-copy">
              <strong>{{ getExecutionProgressMeta(runDetail).headline }}</strong>
              <small>{{ getExecutionProgressMeta(runDetail).subline }}</small>
            </div>
            <div class="execution-progress-bar">
              <span class="execution-progress-bar-fill" :style="{ width: `${getExecutionProgressMeta(runDetail).ratio}%` }"></span>
            </div>
          </div>
          <div class="execution-current-task run-detail-current-task" v-if="getCurrentExecutionTask(runDetail)">
            <span class="execution-current-label">当前焦点</span>
            <strong>{{ getCurrentExecutionTask(runDetail).title }}</strong>
            <small>{{ getCurrentExecutionTask(runDetail).subtitle }}</small>
          </div>
          <div class="execution-task-list run-detail-task-list">
            <div
              v-for="task in buildExecutionTasks(runDetail)"
              :key="task.key"
              class="execution-task-item"
              :class="[
                `status-${String(task.status || '').toLowerCase()}`,
                { current: isCurrentExecutionTask(runDetail, task) }
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
                    @click="toggleTaskRawSteps(runDetail, task.key)"
                  >
                    <span>{{ isTaskRawStepsExpanded(runDetail, task.key) ? '收起原始步骤' : '查看原始步骤' }}</span>
                    <small>{{ task.rawSteps.length }} 条</small>
                  </button>
                  <div v-if="isTaskRawStepsExpanded(runDetail, task.key)" class="task-raw-step-list">
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
            <small>{{ runDetail.steps?.length || 0 }} 个步骤</small>
          </div>
          <div class="agent-step-list run-detail-step-list">
            <div
              v-for="(step, stepIndex) in (runDetail.steps || [])"
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
            v-html="renderMarkdown(runDetail.finalAnswer || '暂无最终回答')"
          ></div>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatApi, docApi, kbApi, skillApi } from '@/api'
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
const rawStepExpandState = ref({})
const executionPanelExpandState = ref({})
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
  return knowledgeBases.value.find((item) => item.id === selectedKbId.value)?.name || ''
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
const markdownRendererVersion = ref(0)
const markdownCache = new Map()
const executionTaskCache = new WeakMap()
let markedInstance = null
let highlightInstance = null
let markdownInitPromise = null
let sessionSyncTimer = 0
let scrollStateFrame = 0
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

function generateSessionId() {
  return `sess_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

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

function extractPlanPayloadFromSteps(steps) {
  const orderedSteps = sortAgentSteps(steps || [])
  for (let index = orderedSteps.length - 1; index >= 0; index--) {
    const step = orderedSteps[index]
    if (step?.stepType !== 'plan' || !step.arguments) {
      continue
    }
    return step.arguments
  }
  return null
}

function extractPlanPayloadsFromSteps(steps) {
  return sortAgentSteps(steps || [])
    .filter((step) => step?.stepType === 'plan' && step.arguments)
    .map((step) => step.arguments)
}

function getSourcePlan(source) {
  const mergedTasks = []
  const indexByKey = new Map()
  const appendTasks = (tasks, appended = false) => {
    if (!Array.isArray(tasks)) {
      return
    }
    tasks.forEach((task, taskIndex) => {
      if (!task || typeof task !== 'object') {
        return
      }
      const normalizedTask = {
        key: task.key || `task-${mergedTasks.length + taskIndex + 1}`,
        title: task.title || '',
        description: task.description || '',
        planOrigin: appended ? 'appended' : 'initial'
      }
      const existingIndex = indexByKey.get(normalizedTask.key)
      if (existingIndex === undefined) {
        indexByKey.set(normalizedTask.key, mergedTasks.length)
        mergedTasks.push(normalizedTask)
        return
      }
      const previous = mergedTasks[existingIndex]
      mergedTasks[existingIndex] = {
        ...previous,
        title: normalizedTask.title || previous.title,
        description: normalizedTask.description || previous.description,
        planOrigin: previous.planOrigin || normalizedTask.planOrigin
      }
    })
  }

  extractPlanPayloadsFromSteps(source?.agentSteps || source?.steps || []).forEach((payload, payloadIndex) => {
    appendTasks(payload?.tasks, payloadIndex > 0)
  })

  if (Array.isArray(source?.agentPlan) && source.agentPlan.length) {
    appendTasks(source.agentPlan, mergedTasks.length > 0)
  }

  if (mergedTasks.length) {
    return mergedTasks
  }
  const planPayload = extractPlanPayloadFromSteps(source?.agentSteps || source?.steps || [])
  return Array.isArray(planPayload?.tasks) ? planPayload.tasks : []
}

function getSourceCurrentActionKey(source) {
  if (source?.currentActionKey) {
    return source.currentActionKey
  }
  const steps = sortAgentSteps(source?.agentSteps || source?.steps || [])
  for (let index = steps.length - 1; index >= 0; index--) {
    const taskKey = steps[index]?.arguments?.taskKey
    if (typeof taskKey === 'string' && taskKey) {
      return taskKey
    }
  }
  const planPayload = extractPlanPayloadFromSteps(steps)
  return typeof planPayload?.currentActionKey === 'string' ? planPayload.currentActionKey : ''
}

function getSourceCompletedTaskKeys(source) {
  if (Array.isArray(source?.completedTaskKeys) && source.completedTaskKeys.length) {
    return source.completedTaskKeys.filter((item) => typeof item === 'string' && item)
  }
  return []
}

function sortAgentSteps(steps) {
  return [...steps].sort((left, right) => {
    const leftIndex = left?.stepIndex ?? 0
    const rightIndex = right?.stepIndex ?? 0
    return leftIndex - rightIndex
  })
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

function getToolStatusClass(status) {
  return {
    SUCCESS: 'success',
    RUNNING: 'warning',
    FAILED: 'danger',
    PARTIAL: 'warning',
    UNFINISHED: 'info',
    PENDING: 'info'
  }[status] || 'info'
}

function formatToolStatus(status) {
  return {
    SUCCESS: '成功',
    RUNNING: '执行中',
    FAILED: '失败',
    PARTIAL: '已执行未收尾',
    UNFINISHED: '未完成',
    PENDING: '待执行'
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

function getExecutionSourceId(source) {
  return source?.runId || source?.createdAt || source?.userGoal || 'execution'
}

function getTaskExpandStateKey(source, taskKey) {
  return `${getExecutionSourceId(source)}:${taskKey}`
}

function getExecutionPanelStateKey(source) {
  return `${getExecutionSourceId(source)}:panel`
}

function isExecutionPanelExpanded(source) {
  if (!source || typeof source !== 'object') {
    return false
  }
  const stateKey = getExecutionPanelStateKey(source)
  const stored = executionPanelExpandState.value[stateKey]
  return stored === undefined ? false : Boolean(stored)
}

function toggleExecutionPanel(source) {
  const stateKey = getExecutionPanelStateKey(source)
  executionPanelExpandState.value = {
    ...executionPanelExpandState.value,
    [stateKey]: !isExecutionPanelExpanded(source)
  }
}

function isTaskRawStepsExpanded(source, taskKey) {
  return Boolean(rawStepExpandState.value[getTaskExpandStateKey(source, taskKey)])
}

function toggleTaskRawSteps(source, taskKey) {
  const stateKey = getTaskExpandStateKey(source, taskKey)
  rawStepExpandState.value = {
    ...rawStepExpandState.value,
    [stateKey]: !rawStepExpandState.value[stateKey]
  }
}

function buildTaskToolBuckets(source, tasks, steps) {
  const toolCalls = Array.isArray(source?.toolCalls) ? [...source.toolCalls] : []
  const taskBuckets = new Map()
  const orphanTools = []

  for (const task of tasks) {
    taskBuckets.set(task.key, [])
  }

  let toolCursor = 0
  const nextMatchingToolCall = (step) => {
    for (let index = toolCursor; index < toolCalls.length; index++) {
      const candidate = toolCalls[index]
      const candidateTaskKey = candidate?.arguments?.taskKey
      const stepTaskKey = step?.arguments?.taskKey
      const taskMatches = candidateTaskKey && stepTaskKey
        ? candidateTaskKey === stepTaskKey
        : true
      const toolMatches = !step?.toolName || !candidate?.toolName || step.toolName === candidate.toolName
      if (taskMatches && toolMatches) {
        toolCursor = index + 1
        return candidate
      }
    }
    return null
  }

  for (const step of steps) {
    if (step?.stepType !== 'tool_call') {
      continue
    }
    const matchedToolCall = nextMatchingToolCall(step)
    if (!matchedToolCall) {
      continue
    }
    const taskKey = step?.arguments?.taskKey
    if (taskKey && taskBuckets.has(taskKey)) {
      taskBuckets.get(taskKey).push(matchedToolCall)
    } else {
      orphanTools.push(matchedToolCall)
    }
  }

  for (; toolCursor < toolCalls.length; toolCursor++) {
    orphanTools.push(toolCalls[toolCursor])
  }

  return { taskBuckets, orphanTools }
}

function formatAgentStepLabel(step) {
  if (!step) {
    return '未知步骤'
  }

  if (step.stepType === 'tool_call') {
    return step.toolName ? `调用工具 · ${step.toolName}` : '调用工具'
  }
  if (step.stepType === 'plan') {
    return '生成任务计划'
  }
  if (step.stepType === 'finish') {
    return '结束并生成回答'
  }
  if (step.stepType === 'respond_with_gap') {
    return '结束并说明信息缺口'
  }
  return step.stepType || '步骤'
}

function formatExecutionTaskTitle(step) {
  if (!step) {
    return '未知任务'
  }
  if (step.stepType === 'tool_call') {
    return step.toolName ? `调用工具 ${step.toolName}` : '调用工具补充信息'
  }
  if (step.stepType === 'finish') {
    return '整理结论并生成最终回答'
  }
  if (step.stepType === 'respond_with_gap') {
    return '说明信息缺口并结束本轮'
  }
  return formatAgentStepLabel(step)
}

function resolveExecutionStatus(source) {
  if (source?.runStatus) {
    return source.runStatus
  }
  if (source?.status) {
    return source.status
  }
  if (source?.content || source?.finalAnswer) {
    return 'SUCCESS'
  }
  return 'RUNNING'
}

function buildExecutionTaskCacheSignature(source, steps, explicitPlan, currentActionKey, completedTaskKeys, executionStatus, finalContent) {
  const stepSignature = steps.map((step) => [
    step?.stepIndex ?? '',
    step?.stepType ?? '',
    step?.toolName ?? '',
    step?.status ?? '',
    step?.durationMs ?? '',
    step?.arguments?.taskKey ?? ''
  ].join(':')).join('|')
  const planSignature = explicitPlan.map((task) => [
    task?.key ?? '',
    task?.title ?? '',
    task?.description ?? ''
  ].join(':')).join('|')
  const completedSignature = Array.from(completedTaskKeys).join('|')
  return [
    source?.runId ?? '',
    source?.status ?? '',
    source?.runStatus ?? '',
    executionStatus,
    currentActionKey ?? '',
    completedSignature,
    finalContent ? '1' : '0',
    planSignature,
    stepSignature
  ].join('~')
}

function buildExecutionTasks(source) {
  if (!source || typeof source !== 'object') {
    return []
  }

  const steps = sortAgentSteps(source?.agentSteps || source?.steps || [])
  const explicitPlan = getSourcePlan(source)
  const currentActionKey = getSourceCurrentActionKey(source)
  const completedTaskKeys = new Set(getSourceCompletedTaskKeys(source))
  const executionStatus = resolveExecutionStatus(source)
  const finalContent = source?.content || source?.finalAnswer || ''
  const rawActionSteps = steps.filter((step) => step?.stepType !== 'plan')
  const cacheSignature = buildExecutionTaskCacheSignature(
    source,
    steps,
    explicitPlan,
    currentActionKey,
    completedTaskKeys,
    executionStatus,
    finalContent
  )
  const cached = executionTaskCache.get(source)

  if (cached?.signature === cacheSignature && Array.isArray(cached.tasks)) {
    return cached.tasks
  }

  if (explicitPlan.length) {
    const currentTaskIndex = explicitPlan.findIndex((task) => task?.key === currentActionKey)
    const finalTaskIndex = explicitPlan.length - 1
    const runFinished = executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL'
    const seededTasks = explicitPlan.map((task, index) => {
      const taskSteps = rawActionSteps.filter((step) => step?.arguments?.taskKey === task.key)
      const matchedStep = taskSteps.at(-1)
      const isCurrent = Boolean(currentActionKey && currentActionKey === task.key)
      const hasSuccessfulActivity = taskSteps.some((step) => step?.status && step.status !== 'FAILED' && step.stepType !== 'plan')
      const hasTerminalStep = taskSteps.some((step) => step?.stepType === 'finish' || step?.stepType === 'respond_with_gap')
      let inferredStatus = 'PENDING'

      if (completedTaskKeys.has(task.key) || hasTerminalStep || (runFinished && finalContent && index === finalTaskIndex)) {
        inferredStatus = 'SUCCESS'
      } else if (matchedStep?.status === 'FAILED') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status === 'PARTIAL') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status === 'RUNNING') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status && !completedTaskKeys.size) {
        inferredStatus = matchedStep.status
      } else if (isCurrent) {
        inferredStatus = 'RUNNING'
      } else if (runFinished) {
        if (hasSuccessfulActivity || (currentTaskIndex >= 0 && index < currentTaskIndex && taskSteps.length)) {
          inferredStatus = 'PARTIAL'
        } else {
          inferredStatus = 'UNFINISHED'
        }
      }

      return {
        key: task.key || `task-${index + 1}`,
        title: task.title || `任务 ${index + 1}`,
        subtitle: matchedStep ? formatAgentStepLabel(matchedStep) : (task.description || ''),
        detail: matchedStep?.observationSummary || matchedStep?.reason || task.description || '',
        planOrigin: task.planOrigin || 'initial',
        status: inferredStatus,
        durationMs: matchedStep?.durationMs ?? null,
        toolName: matchedStep?.toolName || '',
        arguments: matchedStep?.arguments || null,
        rawSteps: taskSteps,
        order: index + 1
      }
    })
    const { taskBuckets, orphanTools } = buildTaskToolBuckets(source, seededTasks, rawActionSteps)
    const normalizedTasks = normalizeExecutionTaskStatuses(
      seededTasks.map((task) => ({
        ...task,
        toolCalls: taskBuckets.get(task.key) || []
      })),
      executionStatus
    )
    const result = normalizedTasks.map((task) => ({
      ...task,
      orphanTools
    }))
    executionTaskCache.set(source, { signature: cacheSignature, tasks: result })
    return result
  }

  const tasks = [
    {
      key: 'plan',
      title: '理解问题并生成执行计划',
      subtitle: source?.userGoal || '',
      detail: steps[0]?.reason || '',
      planOrigin: 'initial',
      status: steps.length || finalContent ? 'SUCCESS' : (executionStatus === 'RUNNING' ? 'RUNNING' : 'PENDING')
    }
  ]

  for (const step of steps) {
    tasks.push({
      key: `step-${step.stepIndex ?? tasks.length}`,
      title: formatExecutionTaskTitle(step),
      subtitle: formatAgentStepLabel(step),
      detail: step.observationSummary || step.reason || '',
      planOrigin: 'initial',
      status: step.status || 'PENDING',
      durationMs: step.durationMs ?? null,
      toolName: step.toolName || '',
      arguments: step.arguments || null,
      rawSteps: [step]
    })
  }

  tasks.push({
    key: 'answer',
    title: '输出最终回答',
    subtitle: finalContent ? '已生成结果' : '等待汇总执行结果',
    detail: '',
    planOrigin: 'initial',
    status: finalContent
      ? 'SUCCESS'
      : (executionStatus === 'RUNNING' ? 'RUNNING' : (executionStatus === 'PARTIAL' ? 'PARTIAL' : 'PENDING'))
  })

  const seededTasks = tasks.map((task, index) => ({
    ...task,
    order: index + 1
  }))
  const { taskBuckets, orphanTools } = buildTaskToolBuckets(source, seededTasks, rawActionSteps)
  const normalizedTasks = normalizeExecutionTaskStatuses(
    seededTasks.map((task) => ({
      ...task,
      toolCalls: taskBuckets.get(task.key) || []
    })),
    executionStatus
  )
  const result = normalizedTasks.map((task) => ({
    ...task,
    orphanTools
  }))
  executionTaskCache.set(source, { signature: cacheSignature, tasks: result })
  return result
}

function normalizeExecutionTaskStatuses(tasks, executionStatus) {
  if (!Array.isArray(tasks) || !tasks.length) {
    return []
  }

  const terminalStatuses = new Set(['SUCCESS', 'FAILED', 'PARTIAL', 'UNFINISHED'])
  const lastTerminalIndex = tasks.reduce((acc, task, index) => {
    return terminalStatuses.has(task?.status) ? index : acc
  }, -1)

  return tasks.map((task, index) => {
    if (!task || task.status !== 'RUNNING') {
      return task
    }

    const hasLaterTerminalTask = lastTerminalIndex > index
    const runClosed = executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL'
    if (!hasLaterTerminalTask && !runClosed) {
      return task
    }

    const toolCalls = Array.isArray(task.toolCalls) ? task.toolCalls : []
    const rawSteps = Array.isArray(task.rawSteps) ? task.rawSteps : []
    const hasFailedSignal = toolCalls.some((item) => item?.status === 'FAILED') || rawSteps.some((item) => item?.status === 'FAILED')
    const hasSuccessSignal = toolCalls.some((item) => item?.status === 'SUCCESS')
      || rawSteps.some((item) => item?.status === 'SUCCESS')
      || rawSteps.some((item) => item?.stepType === 'tool_call' && item?.status && item.status !== 'FAILED' && item.status !== 'RUNNING')

    return {
      ...task,
      status: hasFailedSignal
        ? 'FAILED'
        : (hasSuccessSignal ? 'SUCCESS' : (runClosed ? 'UNFINISHED' : 'PARTIAL'))
    }
  })
}

function getExecutionProgressMeta(source) {
  const tasks = buildExecutionTasks(source)
  if (!tasks.length) {
    return {
      summary: '0 / 0',
      headline: '等待开始',
      subline: '还没有生成可展示的执行任务',
      ratio: 0
    }
  }

  const successCount = tasks.filter((task) => task.status === 'SUCCESS').length
  const runningCount = tasks.filter((task) => task.status === 'RUNNING').length
  const partialCount = tasks.filter((task) => task.status === 'PARTIAL').length
  const unfinishedCount = tasks.filter((task) => task.status === 'UNFINISHED').length
  const failedCount = tasks.filter((task) => task.status === 'FAILED').length
  const ratio = Math.round((successCount / tasks.length) * 100)

  let headline = `已完成 ${successCount} / ${tasks.length}`
  let subline = '正在按计划推进'
  if (failedCount) {
    headline = `${failedCount} 个任务失败`
    subline = successCount
      ? `已完成 ${successCount} 个任务，仍有失败步骤需要处理`
      : '执行中出现失败步骤'
  } else if (runningCount) {
    headline = `进行中 · ${successCount} / ${tasks.length}`
    subline = partialCount
      ? `还有 ${partialCount} 个任务已执行但未完全收尾`
      : '当前任务正在执行'
  } else if (unfinishedCount) {
    headline = `已完成 ${successCount} / ${tasks.length}`
    subline = `还有 ${unfinishedCount} 个任务未完成`
  } else if (partialCount) {
    headline = `已完成 ${successCount} / ${tasks.length}`
    subline = `${partialCount} 个任务已执行但还没有完整收尾`
  } else if (successCount === tasks.length) {
    headline = `全部完成 · ${tasks.length} / ${tasks.length}`
    subline = '整条执行链路已经闭环'
  }

  return {
    summary: `${successCount} / ${tasks.length} 已完成`,
    headline,
    subline,
    ratio
  }
}

function getCurrentExecutionTask(source) {
  const tasks = buildExecutionTasks(source)
  const executionStatus = resolveExecutionStatus(source)
  const activeTask = tasks.find((task) => task.status === 'RUNNING')
    || tasks.find((task) => task.status === 'PENDING')
  if (activeTask) {
    return activeTask
  }
  if (executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL') {
    return null
  }
  return tasks[tasks.length - 1] || null
}

function isCurrentExecutionTask(source, task) {
  if (!task) {
    return false
  }
  const currentTask = getCurrentExecutionTask(source)
  return Boolean(currentTask && currentTask.key === task.key)
}

function getExecutionOrphanTools(source) {
  const tasks = buildExecutionTasks(source)
  return tasks[0]?.orphanTools || []
}

function shortRunId(runId) {
  if (!runId) {
    return ''
  }
  return runId.length <= 16 ? runId : `${runId.slice(0, 12)}...`
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
  if (!selectedKbId.value && knowledgeBases.value.length) {
    selectedKbId.value = knowledgeBases.value[0].id
  }
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
  if (matchedKbId) {
    selectedKbId.value = matchedKbId
  }

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

async function copyRunId(runId) {
  if (!runId) {
    return
  }
  await navigator.clipboard.writeText(runId)
  ElMessage.success('Run ID 已复制')
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
    documentNameKeyword: retrievalOptions.value.documentNameKeyword.trim() || null,
    skillNames: selectedSkillNames.value.length ? selectedSkillNames.value : null
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
  contain: layout paint;
}

.scroll-to-latest-btn {
  position: sticky;
  left: calc(100% - 148px);
  bottom: 132px;
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

.composer-actions {
  position: absolute;
  right: 10px;
  bottom: 10px;
  z-index: 2;
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
  min-width: 118px;
  height: 40px;
  border-radius: 14px;
  background: linear-gradient(135deg, #d8efff, #b9dbff) !important;
  border: 1px solid rgba(255, 124, 124, 0.24) !important;
  box-shadow:
    0 10px 20px rgba(162, 185, 214, 0.18),
    0 0 0 1px rgba(255, 255, 255, 0.58) inset !important;
}

.stop-btn {
  background: linear-gradient(135deg, #fff2d8, #ffd8d8) !important;
  border-color: rgba(239, 68, 68, 0.22) !important;
  color: #9a4b4b !important;
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

  .chat-input-card :deep(.el-textarea__inner) {
    padding-right: 14px !important;
    padding-bottom: 64px !important;
  }

  .composer-actions {
    left: 10px;
    right: 10px;
  }

  .send-btn-inside {
    width: 100%;
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

  .run-detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
