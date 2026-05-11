<template>
  <section v-if="visible" class="retrieval-panel glass-panel">
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
