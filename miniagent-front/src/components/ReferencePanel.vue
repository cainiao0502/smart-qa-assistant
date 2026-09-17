<template>
  <div v-if="message.role === 'assistant' && hasReferenceSource" class="reference-panel" :class="{ open: panelOpen }">
    <!-- 收起态：一栏细长条，点击展开 -->
    <button type="button" class="reference-header" :aria-expanded="panelOpen" @click="panelOpen = !panelOpen">
      <span class="ref-mark" aria-hidden="true"></span>
      <span class="ref-title">参考来源</span>
      <span class="ref-count">
        {{ references.length }}<small> 条命中片段</small>
      </span>
      <svg class="ref-chevron" :class="{ open: panelOpen }" viewBox="0 0 12 12" width="12" height="12" aria-hidden="true">
        <path d="M2.5 4.5 L6 8 L9.5 4.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <!-- 展开态：片段列表（grid rows 过渡，0fr -> 1fr） -->
    <div class="reference-body">
      <div class="reference-body-inner">
        <article
          v-for="(reference, refIndex) in references"
          :key="refIndex"
          class="reference-item"
          :class="{ expanded: isExpanded(refIndex) }"
        >
          <button type="button" class="item-head" :aria-expanded="isExpanded(refIndex)" @click="toggleItem(refIndex)">
            <span class="item-index">{{ String(refIndex + 1).padStart(2, '0') }}</span>
            <span class="item-name" :title="reference.documentName || `文档 #${reference.docId}`">
              {{ reference.documentName || `文档 #${reference.docId}` }}
            </span>
            <span class="item-badges">
              <span v-if="reference.fileType" class="item-badge type">{{ reference.fileType.toUpperCase() }}</span>
              <span v-if="reference.score !== undefined && reference.score !== null" class="item-badge score">
                Score {{ formatScore(reference.score) }}
              </span>
              <span v-if="reference.rerankScore !== undefined && reference.rerankScore !== null" class="item-badge rerank">
                Rerank {{ formatScore(reference.rerankScore) }}
              </span>
            </span>
            <svg class="item-chevron" viewBox="0 0 12 12" width="10" height="10" aria-hidden="true">
              <path d="M4.5 2.5 L8 6 L4.5 9.5" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <div class="item-body">
            <div class="item-body-inner">
              <p v-if="reference.hitReason" class="item-reason">
                <span class="reason-dot" aria-hidden="true"></span>{{ reference.hitReason }}
              </p>
              <p class="item-text">{{ reference.chunkText }}</p>
            </div>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  message: { type: Object, required: true }
})

const references = computed(() => {
  if (Array.isArray(props.message.references)) return props.message.references
  if (typeof props.message.referencesJson === 'string' && props.message.referencesJson !== 'null') {
    try {
      const parsed = JSON.parse(props.message.referencesJson)
      return Array.isArray(parsed) ? parsed : []
    } catch { return [] }
  }
  return []
})

const hasReferenceSource = computed(() => references.value.length > 0)

const panelOpen = ref(false)
const expandedItems = ref(new Set())

function isExpanded(index) {
  return expandedItems.value.has(index)
}

function toggleItem(index) {
  const next = new Set(expandedItems.value)
  next.has(index) ? next.delete(index) : next.add(index)
  expandedItems.value = next
}

function formatScore(score) {
  return Number(score).toFixed(3)
}
</script>

<style scoped>
/* ── 面板：收起时是一栏细长条，展开时露出片段列表 ───────────── */
.reference-panel {
  margin-top: 8px;
  border: 1px solid color-mix(in srgb, var(--accent-color, #a63d2a) 16%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 5%, transparent);
  overflow: hidden;
  transition: border-color 0.2s ease, background 0.2s ease;
}
.reference-panel:hover,
.reference-panel.open {
  border-color: color-mix(in srgb, var(--accent-color, #a63d2a) 30%, transparent);
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 7%, transparent);
}

/* ── 收起栏 ─────────────────────────────────────────────────── */
.reference-header {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 12px;
  min-height: 38px;
  border: none;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.reference-header:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-color, #a63d2a) 55%, transparent);
  outline-offset: -2px;
}
.ref-mark {
  width: 8px;
  height: 8px;
  flex: none;
  background: var(--accent-color, #a63d2a);
  border-radius: 2px;
  transform: rotate(45deg);
  opacity: 0.85;
}
.ref-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: 0.02em;
}
.ref-count {
  margin-left: auto;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--accent-color, #a63d2a);
}
.ref-count small {
  font-family: inherit;
  color: var(--text-muted);
  margin-left: 2px;
}
.ref-chevron {
  flex: none;
  color: var(--text-muted);
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.ref-chevron.open {
  transform: rotate(180deg);
}

/* ── 列表：grid rows 过渡实现平滑展开 ───────────────────────── */
.reference-body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.26s cubic-bezier(0.16, 1, 0.3, 1);
}
.reference-panel.open .reference-body {
  grid-template-rows: 1fr;
}
.reference-body-inner {
  overflow: hidden;
}
.reference-panel.open .reference-body-inner {
  border-top: 1px solid color-mix(in srgb, var(--accent-color, #a63d2a) 12%, transparent);
  max-height: 340px;
  overflow-y: auto;
}

/* ── 单条片段：头部一行，点击展开全文 ───────────────────────── */
.reference-item {
  border-bottom: 1px dashed color-mix(in srgb, var(--accent-color, #a63d2a) 10%, transparent);
}
.reference-item:last-child { border-bottom: none; }

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background 0.15s ease;
}
.item-head:hover {
  background: color-mix(in srgb, var(--accent-color, #a63d2a) 6%, transparent);
}
.item-head:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-color, #a63d2a) 55%, transparent);
  outline-offset: -2px;
}
.item-index {
  flex: none;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  color: var(--accent-color, #a63d2a);
  opacity: 0.8;
}
.item-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-badges {
  display: flex;
  gap: 4px;
  flex: none;
}
.item-badge {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.item-badge.type {
  color: var(--text-muted);
  border-color: color-mix(in srgb, var(--text-muted) 30%, transparent);
}
.item-badge.score {
  color: #4d8b4f;
  background: rgba(76, 175, 80, 0.12);
}
.item-badge.rerank {
  color: #3d7ab5;
  background: rgba(33, 150, 243, 0.12);
}
.item-chevron {
  flex: none;
  color: var(--text-muted);
  transition: transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
.reference-item.expanded .item-chevron {
  transform: rotate(90deg);
}

/* ── 条目正文：grid rows 过渡展开全文 ───────────────────────── */
.item-body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}
.reference-item.expanded .item-body {
  grid-template-rows: 1fr;
}
.item-body-inner {
  overflow: hidden;
  padding: 0 12px;
}
.reference-item.expanded .item-body-inner {
  padding-bottom: 10px;
}
.item-reason {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.reason-dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent-color, #a63d2a);
  opacity: 0.6;
  transform: translateY(-2px);
}
.item-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary, #aaa);
  word-break: break-word;
  white-space: pre-wrap;
}

@media (prefers-reduced-motion: reduce) {
  .reference-body,
  .item-body,
  .ref-chevron,
  .item-chevron,
  .reference-panel,
  .item-head {
    transition: none;
  }
}
</style>
