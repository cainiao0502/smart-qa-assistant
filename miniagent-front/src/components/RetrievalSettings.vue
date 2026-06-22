<template>
  <section v-if="visible" class="retrieval-panel">
    <div class="retrieval-grid">
      <div class="setting-field">
        <span class="setting-label">Skill</span>
        <el-select
          :model-value="selectedSkillNames"
          @update:model-value="$emit('update:selectedSkillNames', $event)"
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
        <el-input-number :model-value="retrievalOptions.topK" @update:model-value="updateOption('topK', $event)" :min="1" :max="20" :disabled="!isKnowledgeBaseMode" />
      </div>

      <div class="setting-field">
        <span class="setting-label">相似度阈值</span>
        <div class="setting-inline">
          <el-slider :model-value="retrievalOptions.scoreThreshold" @update:model-value="updateOption('scoreThreshold', $event)" :min="0" :max="1" :step="0.05" :disabled="!isKnowledgeBaseMode" />
          <span class="setting-value">{{ retrievalOptions.scoreThreshold.toFixed(2) }}</span>
        </div>
      </div>

      <div class="setting-field">
        <span class="setting-label">文档类型过滤</span>
        <el-select
          :model-value="retrievalOptions.fileTypes"
          @update:model-value="updateOption('fileTypes', $event)"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="不过滤文档类型"
          :disabled="!isKnowledgeBaseMode"
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
          :model-value="retrievalOptions.documentNameKeyword"
          @update:model-value="updateOption('documentNameKeyword', $event)"
          placeholder="例如：guide、部署、FAQ"
          clearable
          :disabled="!isKnowledgeBaseMode"
        />
      </div>

      <div class="setting-field setting-field-full">
        <span class="setting-label">指定文档范围</span>
        <el-select
          :model-value="retrievalOptions.documentIds"
          @update:model-value="updateOption('documentIds', $event)"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="默认检索当前知识库全部文档"
          :disabled="!selectedKbId"
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
      <span class="setting-tip">{{ isKnowledgeBaseMode ? '这些设置只影响当前会话后续提问，不会修改知识库数据。' : '快速对话偏向低延迟；Agent增强会保留规划与工具能力。' }}</span>
      <el-button text @click="$emit('reset')">恢复默认</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  retrievalOptions: { type: Object, required: true },
  selectedSkillNames: { type: Array, default: () => [] },
  availableSkills: { type: Array, default: () => [] },
  availableDocuments: { type: Array, default: () => [] },
  isKnowledgeBaseMode: Boolean,
  selectedKbId: { default: null }
})

const emit = defineEmits(['update:retrievalOptions', 'update:selectedSkillNames', 'reset'])

const fileTypeOptions = ['pdf', 'txt', 'md', 'doc', 'docx']

const executableSkillCount = computed(() => {
  return props.availableSkills.filter((item) => item.executable).length
})

function updateOption(key, value) {
  emit('update:retrievalOptions', { ...props.retrievalOptions, [key]: value })
}
</script>

<style scoped>
.retrieval-panel {
  padding: 20px 22px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(248, 252, 255, 0.96));
  border: 1px solid rgba(210, 225, 240, 0.35);
  box-shadow: 0 4px 20px rgba(164, 182, 207, 0.08);
  backdrop-filter: blur(12px) saturate(140%);
  -webkit-backdrop-filter: blur(12px) saturate(140%);
}

.retrieval-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 20px;
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
  color: #7a8ca4;
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
  color: #3d5068;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
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
  margin-top: 4px;
  color: #9aa8b8;
  font-size: 11px;
  line-height: 1.5;
}

.retrieval-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.setting-tip {
  color: #9aa8b8;
  font-size: 11.5px;
}

/* 覆盖 Element Plus 表单元素样式，使其更圆润柔和 */
:deep(.el-select .el-input__wrapper),
:deep(.el-input__wrapper) {
  border-radius: 14px !important;
  background: rgba(247, 250, 253, 0.9) !important;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.03), 0 0 0 1px rgba(210, 222, 236, 0.5) inset !important;
  padding: 4px 12px !important;
  transition: all 0.2s ease !important;
}

:deep(.el-select .el-input__wrapper:hover),
:deep(.el-input__wrapper:hover) {
  background: rgba(252, 253, 255, 0.96) !important;
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.02), 0 0 0 1px rgba(180, 200, 222, 0.6) inset !important;
}

:deep(.el-select .el-input__wrapper.is-focus),
:deep(.el-input__wrapper.is-focus) {
  background: #ffffff !important;
  box-shadow: 0 0 0 1.5px rgba(91, 108, 255, 0.3) inset, 0 0 0 3px rgba(91, 108, 255, 0.06) !important;
}

:deep(.el-input-number) {
  width: 100%;
}

:deep(.el-input-number .el-input__wrapper) {
  border-radius: 14px !important;
}

:deep(.el-input-number__decrease),
:deep(.el-input-number__increase) {
  border-radius: 10px !important;
  background: rgba(241, 245, 249, 0.9) !important;
  border: none !important;
}

:deep(.el-slider__runway) {
  height: 4px;
  border-radius: 999px;
  background: rgba(220, 230, 240, 0.8);
}

:deep(.el-slider__bar) {
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(91, 108, 255, 0.6), rgba(91, 108, 255, 0.8));
}

:deep(.el-slider__button) {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(91, 108, 255, 0.6);
  box-shadow: 0 2px 6px rgba(91, 108, 255, 0.2);
}

:deep(.el-select__placeholder),
:deep(.el-input__inner),
:deep(.el-input__inner::placeholder) {
  font-size: 13px !important;
}

:deep(.el-tag) {
  border-radius: 999px !important;
  font-size: 11px !important;
}
</style>
