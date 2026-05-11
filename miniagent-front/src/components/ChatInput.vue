<template>
  <footer class="chat-input-container">
    <div class="input-wrapper">
      <textarea
        :value="question"
        @input="$emit('update:question', $event.target.value)"
        class="chat-textarea"
        :rows="1"
        placeholder="输入你的问题，按 Enter 发送"
        @keydown.enter.exact.prevent="$emit('send')"
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
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <rect x="6" y="6" width="12" height="12" rx="2"/>
            </svg>
          </button>
          <button
            v-else
            type="button"
            class="send-btn"
            :class="{ disabled: !question.trim() }"
            :disabled="!question.trim()"
            @click="$emit('send')"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 2L11 13M22 2L15 22L11 13M22 2L2 9L11 13"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'

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

defineEmits(['update:question', 'send', 'stop'])

const modeLabel = computed(() => {
  if (props.isKnowledgeBaseMode) return 'RAG'
  return props.assistantMode === 'agent' ? 'Agent' : '对话'
})
</script>

<style scoped>
.chat-input-container {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 16px 20px 20px;
  background: linear-gradient(to top, rgba(255,255,255,0.98) 60%, rgba(255,255,255,0.9) 85%, transparent);
  pointer-events: none;
}

.input-wrapper {
  position: relative;
  max-width: 800px;
  margin: 0 auto;
  background: #fff;
  border-radius: 20px;
  box-shadow:
    0 2px 8px rgba(0,0,0,0.04),
    0 8px 24px rgba(0,0,0,0.06),
    0 0 0 1px rgba(0,0,0,0.06);
  pointer-events: auto;
  transition: box-shadow 0.2s ease;
  display: flex;
  flex-direction: column;
}

.input-wrapper:focus-within {
  box-shadow:
    0 2px 8px rgba(0,0,0,0.04),
    0 8px 32px rgba(0,0,0,0.1),
    0 0 0 2px rgba(59, 130, 246, 0.3);
}

.chat-textarea {
  display: block;
  width: 100%;
  min-height: 48px;
  max-height: 180px;
  padding: 14px 16px;
  border: none;
  outline: none;
  resize: none;
  font-size: 15px;
  line-height: 1.6;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  color: #1a1a1a;
  background: transparent;
}

.chat-textarea::placeholder {
  color: #9ca3af;
}

.input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fafafa;
  border-top: 1px solid #f0f0f0;
  border-radius: 0 0 20px 20px;
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
  border-radius: 12px;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  transition: all 0.2s ease;
}

.mode-tag.active {
  background: #ecfdf5;
  color: #059669;
}

.mode-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.kb-name {
  font-size: 13px;
  color: #9ca3af;
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
  border-radius: 8px;
  background: #f3f4f6;
  color: #9ca3af;
  font-size: 11px;
  font-weight: 500;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
}

.send-btn:hover:not(.disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.4);
}

.send-btn:active:not(.disabled) {
  transform: translateY(0);
}

.send-btn.disabled {
  background: #e5e7eb;
  color: #9ca3af;
  cursor: not-allowed;
  box-shadow: none;
}

.send-btn.stop {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.3);
}

.send-btn.stop:hover {
  box-shadow: 0 4px 12px rgba(239, 68, 68, 0.4);
}

@media (max-width: 768px) {
  .chat-input-container {
    padding: 12px 12px 16px;
  }
  
  .input-wrapper {
    border-radius: 16px;
  }
  
  .chat-textarea {
    padding: 12px 14px;
    padding-bottom: 42px;
    font-size: 16px;
  }
  
  .input-toolbar {
    padding: 6px 10px;
  }
  
  .kb-name {
    display: none;
  }
  
  .session-badge {
    display: none;
  }
}
</style>