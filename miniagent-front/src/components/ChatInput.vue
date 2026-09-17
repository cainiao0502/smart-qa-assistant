<template>
  <footer class="chat-input-container">
    <div class="input-wrapper">
      <textarea
        :value="question"
        @input="$emit('update:question', $event.target.value)"
        class="chat-textarea"
        :rows="1"
        placeholder="输入你的问题，按 Enter 发送"
        @keydown.enter.exact.prevent="handleSend"
      ></textarea>

      <div class="input-toolbar">
        <div class="toolbar-left">
          <span class="mode-tag" :class="{ active: isKnowledgeBaseMode }">
            <span class="mode-dot"></span>
            {{ modeLabel }}
          </span>
          <span class="kb-name">{{ currentKnowledgeBaseName || '通用助手' }}</span>
        </div>

        <div class="toolbar-right">
          <span v-if="sessionId" class="session-badge">{{ sessionId.slice(-6) }}</span>
          <button
            v-if="isLoading"
            type="button"
            class="send-btn stop"
            @click="$emit('stop')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
            停止
          </button>
          <button
            v-else
            type="button"
            class="send-btn"
            :disabled="!question.trim() || isSent"
            @click="handleSend"
          >
            发送
          </button>
        </div>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  question: { type: String, default: '' },
  isLoading: Boolean,
  assistantMode: { type: String, default: 'fast' },
  isKnowledgeBaseMode: Boolean,
  currentKnowledgeBaseName: { type: String, default: '通用助手' },
  selectedSkillNames: { type: Array, default: () => [] },
  selectedExecutableSkillCount: { type: Number, default: 0 },
  retrievalOptions: { type: Object, required: true },
  sessionId: { type: String, default: '' }
})

const emit = defineEmits(['update:question', 'send', 'stop'])

/* isSent 仅作防抖禁用；发送反馈交给消息列表入场动画，按钮保持安静 */
const isSent = ref(false)

const handleSend = () => {
  if (!props.question.trim() || isSent.value) return
  emit('send')
  isSent.value = true
  setTimeout(() => {
    isSent.value = false
  }, 1200)
}

const modeLabel = computed(() => {
  if (props.isKnowledgeBaseMode) return 'RAG'
  return props.assistantMode === 'agent' ? 'Agent' : '对话'
})
</script>

<style scoped>
/* 宣纸墨韵 · 文档流输入区（grid 第四行，替代原 absolute 悬浮 + 220px 让位 hack） */
.chat-input-container {
  grid-row: 4;
  padding: 0;
}

.input-wrapper {
  max-width: 800px;
  margin: 0 auto;
  background: var(--bg-surface-strong, #fbf9f3);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-xs);
  display: flex;
  flex-direction: column;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.input-wrapper:focus-within {
  border-color: var(--border-medium);
  box-shadow: var(--shadow-sm);
}

.chat-textarea {
  display: block;
  width: 100%;
  min-height: 48px;
  max-height: 160px;
  padding: 13px 16px;
  border: none;
  outline: none;
  resize: none;
  font-size: var(--text-md);
  line-height: 1.7;
  font-family: inherit;
  color: var(--text-primary);
  background: transparent;
}

.chat-textarea::placeholder {
  color: var(--text-muted);
}

.input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-top: 1px solid var(--border-light);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.mode-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: var(--radius-xs);
  background: var(--bg-surface-dark, #efe9db);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
}

.mode-tag.active {
  background: var(--primary-soft);
  color: var(--primary-strong);
}

.mode-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.kb-name {
  font-size: var(--text-sm);
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.session-badge {
  padding: 3px 8px;
  border-radius: var(--radius-xs);
  background: var(--bg-surface-dark, #efe9db);
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  font-family: var(--font-mono, Consolas, monospace);
}

/* 印章式发送按钮：宋体宽字距，按下如钤印（140ms 临界阻尼，无弹跳） */
.send-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 20px;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--primary-color);
  color: var(--text-inverse, #f9f4ea);
  font-family: var(--font-serif, serif);
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  letter-spacing: 0.2em;
  text-indent: 0.2em; /* 抵消末字字距，视觉居中 */
  cursor: pointer;
  transition: background var(--transition-fast), transform var(--transition-fast);
}

.send-btn:hover:not(:disabled) {
  background: var(--primary-strong);
}

.send-btn:active:not(:disabled) {
  transform: scale(0.97);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.send-btn.stop {
  background: var(--danger-color);
  letter-spacing: 0.08em;
  text-indent: 0.08em;
}

.send-btn.stop:hover {
  background: var(--primary-strong);
}

@media (max-width: 768px) {
  .input-wrapper {
    border-radius: var(--radius-sm);
  }

  .chat-textarea {
    padding: 12px 14px;
    font-size: 16px; /* 移动端防 iOS 聚焦缩放 */
  }

  .input-toolbar {
    padding: 6px 10px;
  }

  .kb-name,
  .session-badge {
    display: none;
  }

  .send-btn {
    height: 40px; /* 触屏目标 ≥40px */
  }
}

@media (prefers-reduced-motion: reduce) {
  .send-btn {
    transition: none;
  }

  .send-btn:active:not(:disabled) {
    transform: none;
  }
}
</style>
