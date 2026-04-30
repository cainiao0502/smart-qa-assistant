<template>
  <section class="skill-page">
    <div class="skill-shell">
      <header class="skill-toolbar">
        <div class="toolbar-copy">
          <h1>Skill 管理</h1>
          <p>支持导入、创建、编辑和删除本地 `SKILL.md`，并区分 Prompt Skill 与可执行 Skill。</p>
        </div>
        <div class="skill-toolbar-actions">
          <el-upload
            class="skill-upload"
            :auto-upload="false"
            :show-file-list="false"
            accept=".md,text/markdown"
            :on-change="handleImportSelect"
          >
            <el-button :loading="importing">导入 MD</el-button>
          </el-upload>
          <el-button type="primary" @click="openCreateDialog">新建 Skill</el-button>
          <el-button :loading="reloading" @click="reloadSkills">重新加载</el-button>
        </div>
      </header>

      <div class="skill-layout">
        <section class="skill-list-panel">
          <div class="skill-list-header">
            <span>本地 Skills</span>
            <small>{{ filteredSkills.length }} / {{ skills.length }}</small>
          </div>

          <el-input
            v-model="skillSearchKeyword"
            class="skill-search"
            clearable
            placeholder="搜索 skill 名称、标题或描述"
          />

          <div v-if="filteredSkills.length" class="skill-list">
            <button
              v-for="skill in filteredSkills"
              :key="skill.name"
              type="button"
              class="skill-item"
              :class="{ active: selectedSkillName === skill.name }"
              @click="selectSkill(skill.name)"
            >
              <div class="skill-item-top">
                <strong>{{ skill.title || skill.name }}</strong>
                <el-tag size="small" :type="skill.executable ? 'success' : 'info'" effect="plain">
                  {{ skill.executable ? '可执行' : 'Prompt' }}
                </el-tag>
              </div>
              <span>{{ skill.description || '暂无描述' }}</span>
              <div class="skill-item-meta">
                <code>{{ skill.name }}</code>
                <code v-if="skill.toolName">{{ skill.toolName }}</code>
              </div>
            </button>
          </div>

          <div v-else class="skill-empty">
            {{ skills.length ? '没有匹配的 skill，试试别的关键词。' : '还没有本地 skill。可以导入 `SKILL.md`，也可以手动创建。' }}
          </div>
        </section>

        <section class="skill-detail-panel">
          <template v-if="detailLoading">
            <el-skeleton :rows="10" animated />
          </template>

          <template v-else-if="skillDetail">
            <div class="skill-detail-header">
              <div class="detail-title-block">
                <div class="detail-title-row">
                  <h2>{{ skillDetail.title || skillDetail.name }}</h2>
                  <el-tag size="small" :type="skillDetail.executable ? 'success' : 'info'" effect="plain">
                    {{ skillDetail.executable ? '可执行 Skill' : 'Prompt Skill' }}
                  </el-tag>
                </div>
                <p>{{ skillDetail.description || '暂无描述' }}</p>
              </div>
              <code class="skill-name-code">{{ skillDetail.name }}</code>
            </div>

            <div class="meta-grid">
              <div class="meta-card">
                <span>来源</span>
                <code>{{ skillDetail.sourcePath }}</code>
              </div>
              <div class="meta-card">
                <span>Tool Name</span>
                <code>{{ skillDetail.toolName || '-' }}</code>
              </div>
              <div class="meta-card">
                <span>Executor</span>
                <code>{{ skillDetail.executorType || '-' }}</code>
              </div>
            </div>

            <div class="detail-actions">
              <el-button @click="openEditDialog">编辑</el-button>
              <el-button type="danger" plain :loading="deleting" @click="deleteCurrentSkill">删除</el-button>
            </div>

            <div v-if="skillDetail.executable" class="debug-panel">
              <div class="debug-panel-header">
                <div>
                  <strong>调试执行</strong>
                  <p>直接对当前可执行 skill 发起一次调试调用，查看输出和 trace。</p>
                </div>
                <el-tag size="small" type="success" effect="plain">
                  {{ skillDetail.executorType || 'executable' }}
                </el-tag>
              </div>

              <div class="debug-grid">
                <el-form-item label="问题" class="debug-span-2">
                  <el-input
                    v-model="debugForm.question"
                    type="textarea"
                    :autosize="{ minRows: 3, maxRows: 5 }"
                    placeholder="例如：基于这个 skill 的规则，给出一个实现建议。"
                  />
                </el-form-item>

                <template v-if="hasDebugSchema">
                  <div
                    v-for="field in debugSchemaFields"
                    :key="field.key"
                    class="schema-field"
                    :class="{ 'debug-span-2': field.type !== 'boolean' && !field.enumValues }"
                  >
                    <span class="schema-label">
                      {{ field.title }}
                      <span v-if="field.required" class="schema-required">*</span>
                    </span>
                    <span v-if="field.description" class="schema-description">{{ field.description }}</span>
                    <span v-if="field.tipText" class="schema-tip">{{ field.tipText }}</span>
                    <el-select
                      v-if="field.enumValues"
                      v-model="debugSchemaValues[field.key]"
                      class="schema-control"
                      @change="clearDebugValidationError(field.key)"
                    >
                      <el-option
                        v-for="option in field.enumValues"
                        :key="String(option)"
                        :label="String(option)"
                        :value="option"
                      />
                    </el-select>
                    <el-switch
                      v-else-if="field.type === 'boolean'"
                      v-model="debugSchemaValues[field.key]"
                      @change="clearDebugValidationError(field.key)"
                    />
                    <el-input-number
                      v-else-if="field.type === 'number' || field.type === 'integer'"
                      v-model="debugSchemaValues[field.key]"
                      :step="field.type === 'integer' ? 1 : 0.1"
                      :precision="field.type === 'integer' ? 0 : 2"
                      class="schema-control"
                      @change="clearDebugValidationError(field.key)"
                    />
                    <el-input
                      v-else
                      v-model="debugSchemaValues[field.key]"
                      class="schema-control"
                      @input="clearDebugValidationError(field.key)"
                    />
                    <span v-if="debugValidationErrors[field.key]" class="schema-error">
                      {{ debugValidationErrors[field.key] }}
                    </span>
                  </div>
                  <div class="debug-span-2 preview-block">
                    <span class="debug-result-label">参数预览</span>
                    <pre class="debug-result-box">{{ generatedArgumentsPreview }}</pre>
                  </div>
                </template>

                <el-form-item v-else label="参数 JSON" class="debug-span-2">
                  <el-input
                    v-model="debugForm.argumentsText"
                    type="textarea"
                    :autosize="{ minRows: 4, maxRows: 8 }"
                    placeholder='例如：{"language":"java","mode":"review"}'
                  />
                </el-form-item>
              </div>

              <div class="debug-actions">
                <el-button type="primary" :loading="debugging" @click="runSkillDebug">
                  调试执行
                </el-button>
                <el-button :loading="savingDebugSample" @click="saveDebugSample">
                  保存为样例
                </el-button>
                <el-button @click="resetDebugForm">清空</el-button>
              </div>

              <div v-if="debugResult" class="debug-result">
                <div class="debug-result-section">
                  <span class="debug-result-label">摘要</span>
                  <div class="debug-result-box">{{ debugResult.summary || '-' }}</div>
                </div>
                <div class="debug-result-section">
                  <span class="debug-result-label">输出</span>
                  <pre class="debug-result-box">{{ debugResult.output || '-' }}</pre>
                </div>
                <div class="debug-result-section">
                  <span class="debug-result-label">Trace</span>
                  <pre class="debug-result-box">{{ formatDebugTrace(debugResult.trace) }}</pre>
                </div>
              </div>
            </div>

            <div class="skill-content">
              <pre>{{ skillDetail.content }}</pre>
            </div>
          </template>

          <div v-else class="skill-detail-empty">
            选择左侧一个 skill 查看详情。
          </div>
        </section>
      </div>
    </div>

    <el-dialog
      v-model="editorDialogVisible"
      :title="editorMode === 'create' ? '新建 Skill' : '编辑 Skill'"
      width="760px"
      destroy-on-close
    >
      <el-form label-position="top" class="editor-form">
        <div class="form-grid">
          <el-form-item label="Skill 名称">
            <el-input v-model="editorForm.name" placeholder="例如：codeagent 或 api-helper" />
          </el-form-item>

          <el-form-item label="标题">
            <el-input v-model="editorForm.title" placeholder="例如：Code Agent" />
          </el-form-item>

          <el-form-item label="描述" class="form-span-2">
            <el-input
              v-model="editorForm.description"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
              placeholder="一句话说明这个 skill 用来做什么。"
            />
          </el-form-item>

          <el-form-item label="Tool Name">
            <el-input v-model="editorForm.toolName" placeholder="留空表示普通 Prompt Skill" />
          </el-form-item>

          <el-form-item label="Executor Type">
            <el-input v-model="editorForm.executorType" placeholder="例如：llm_transform" />
          </el-form-item>

          <el-form-item label="参数 Schema JSON" class="form-span-2">
            <el-input
              v-model="editorForm.debugArgumentsSchemaJson"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 8 }"
              placeholder='例如：{"type":"object","properties":{"language":{"type":"string","title":"语言","enum":["java","python"]},"strict":{"type":"boolean","title":"严格模式"}}}'
            />
          </el-form-item>

          <el-form-item label="Markdown 内容" class="form-span-2">
            <el-input
              v-model="editorForm.content"
              type="textarea"
              :autosize="{ minRows: 12, maxRows: 18 }"
              placeholder="# Skill Title&#10;&#10;在这里填写 skill 正文。"
            />
          </el-form-item>
        </div>

        <el-checkbox v-if="editorMode === 'create'" v-model="editorForm.overwrite">
          如果同名已存在则覆盖
        </el-checkbox>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="editorDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submitEditor">
            {{ editorMode === 'create' ? '创建 Skill' : '保存修改' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      title="导入 Skill"
      width="540px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="文件">
          <el-input :model-value="pendingImport.fileName" disabled />
        </el-form-item>
        <el-form-item label="Skill 名称">
          <el-input v-model="pendingImport.name" placeholder="默认使用文件名" />
        </el-form-item>
        <el-checkbox v-model="pendingImport.overwrite">如果同名已存在则覆盖</el-checkbox>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="clearPendingImport">取消</el-button>
          <el-button type="primary" :loading="importing" @click="confirmImport">开始导入</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { skillApi } from '@/api'

const skills = ref([])
const skillSearchKeyword = ref('')
const selectedSkillName = ref('')
const skillDetail = ref(null)
const detailLoading = ref(false)
const reloading = ref(false)
const importing = ref(false)
const saving = ref(false)
const deleting = ref(false)
const debugging = ref(false)
const savingDebugSample = ref(false)

const editorDialogVisible = ref(false)
const editorMode = ref('create')
const importDialogVisible = ref(false)
const editingOriginalName = ref('')

const editorForm = reactive({
  name: '',
  title: '',
  description: '',
  toolName: '',
  executorType: '',
  debugArgumentsSchemaJson: '',
  content: '',
  overwrite: false
})

const pendingImport = reactive({
  file: null,
  fileName: '',
  name: '',
  overwrite: false
})

const debugForm = reactive({
  question: '',
  argumentsText: ''
})

const debugSchemaValues = reactive({})
const debugResult = ref(null)
const debugValidationErrors = ref({})

const filteredSkills = computed(() => {
  const keyword = skillSearchKeyword.value.trim().toLowerCase()
  if (!keyword) {
    return skills.value
  }
  return skills.value.filter((skill) => {
    return [skill.name, skill.title, skill.description]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword))
  })
})

function parseSchemaDefinition(schemaText) {
  if (!schemaText || !schemaText.trim()) {
    return null
  }
  const parsed = JSON.parse(schemaText)
  if (!parsed || parsed.type !== 'object' || typeof parsed.properties !== 'object') {
    return null
  }
  return parsed
}

const parsedDebugSchema = computed(() => {
  try {
    return parseSchemaDefinition(skillDetail.value?.debugArgumentsSchemaJson || '')
  } catch {
    return null
  }
})

const debugSchemaFields = computed(() => {
  const properties = parsedDebugSchema.value?.properties || {}
  const requiredSet = new Set(parsedDebugSchema.value?.required || [])
  return Object.entries(properties).map(([key, definition]) => ({
    key,
    title: definition?.title || key,
    description: definition?.description || '',
    type: definition?.type || 'string',
    enumValues: Array.isArray(definition?.enum) ? definition.enum : null,
    defaultValue: definition?.default,
    required: requiredSet.has(key),
    minimum: definition?.minimum,
    maximum: definition?.maximum,
    minLength: definition?.minLength,
    maxLength: definition?.maxLength,
    tipText: buildFieldTipText(definition, requiredSet.has(key))
  }))
})

const hasDebugSchema = computed(() => debugSchemaFields.value.length > 0)

const generatedArgumentsPreview = computed(() => {
  if (!hasDebugSchema.value) {
    return debugForm.argumentsText || '{}'
  }
  const result = {}
  for (const field of debugSchemaFields.value) {
    result[field.key] = coerceSchemaValue(field, debugSchemaValues[field.key])
  }
  return JSON.stringify(result, null, 2)
})

function resetEditorForm() {
  editorForm.name = ''
  editorForm.title = ''
  editorForm.description = ''
  editorForm.toolName = ''
  editorForm.executorType = ''
  editorForm.debugArgumentsSchemaJson = ''
  editorForm.content = ''
  editorForm.overwrite = false
}

function resetDebugForm() {
  debugForm.question = ''
  debugForm.argumentsText = ''
  Object.keys(debugSchemaValues).forEach((key) => {
    delete debugSchemaValues[key]
  })
  debugValidationErrors.value = {}
  debugResult.value = null
}

function syncDebugFormFromDetail() {
  debugForm.question = skillDetail.value?.debugQuestion || ''
  debugForm.argumentsText = skillDetail.value?.debugArgumentsJson || ''
  hydrateDebugSchemaValues()
  debugValidationErrors.value = {}
  debugResult.value = null
}

function coerceSchemaValue(field, value) {
  if (field.type === 'number' || field.type === 'integer') {
    if (value === '' || value === null || value === undefined) {
      return null
    }
    const numericValue = Number(value)
    return Number.isFinite(numericValue) ? numericValue : null
  }
  if (field.type === 'boolean') {
    return Boolean(value)
  }
  return value ?? ''
}

function buildFieldTipText(definition, required) {
  const tips = []
  if (required) {
    tips.push('Required')
  }
  if (Array.isArray(definition?.enum) && definition.enum.length) {
    tips.push(`Options: ${definition.enum.join(' / ')}`)
  }
  if (definition?.type === 'integer' || definition?.type === 'number') {
    if (definition?.minimum !== undefined && definition?.maximum !== undefined) {
      tips.push(`Range: ${definition.minimum} - ${definition.maximum}`)
    } else if (definition?.minimum !== undefined) {
      tips.push(`Min: ${definition.minimum}`)
    } else if (definition?.maximum !== undefined) {
      tips.push(`Max: ${definition.maximum}`)
    }
  }
  if (definition?.type === 'string') {
    if (definition?.minLength !== undefined && definition?.maxLength !== undefined) {
      tips.push(`Length: ${definition.minLength} - ${definition.maxLength}`)
    } else if (definition?.minLength !== undefined) {
      tips.push(`Min length: ${definition.minLength}`)
    } else if (definition?.maxLength !== undefined) {
      tips.push(`Max length: ${definition.maxLength}`)
    }
  }
  return tips.join('  |  ')
}
function clearDebugValidationError(fieldKey) {
  if (!fieldKey || !debugValidationErrors.value[fieldKey]) {
    return
  }
  const nextErrors = { ...debugValidationErrors.value }
  delete nextErrors[fieldKey]
  debugValidationErrors.value = nextErrors
}

function buildDefaultSchemaValue(field) {
  if (field.defaultValue !== undefined && field.defaultValue !== null) {
    return field.defaultValue
  }
  if (field.enumValues?.length) {
    return field.enumValues[0]
  }
  if (field.type === 'boolean') {
    return false
  }
  if (field.type === 'number' || field.type === 'integer') {
    return 0
  }
  return ''
}

function hydrateDebugSchemaValues() {
  Object.keys(debugSchemaValues).forEach((key) => delete debugSchemaValues[key])
  if (!hasDebugSchema.value) {
    return
  }

  let parsedArguments = {}
  if (debugForm.argumentsText.trim()) {
    try {
      parsedArguments = JSON.parse(debugForm.argumentsText)
    } catch {
      parsedArguments = {}
    }
  }

  for (const field of debugSchemaFields.value) {
    const incomingValue = parsedArguments[field.key]
    debugSchemaValues[field.key] = incomingValue !== undefined
      ? incomingValue
      : buildDefaultSchemaValue(field)
  }
}

function normalizeImportedName(fileName) {
  return String(fileName || '')
    .replace(/\.md$/i, '')
    .trim()
}

async function loadSkills(preferredName = '') {
  const response = await skillApi.list()
  skills.value = response.data || []
  const nextName = preferredName || selectedSkillName.value || skills.value[0]?.name || ''
  selectedSkillName.value = nextName
  if (nextName) {
    await selectSkill(nextName)
  } else {
    skillDetail.value = null
  }
}

async function selectSkill(name) {
  if (!name) return
  selectedSkillName.value = name
  detailLoading.value = true
  try {
    const response = await skillApi.getDetail(name)
    skillDetail.value = response.data || null
    syncDebugFormFromDetail()
  } catch (error) {
    ElMessage.error(error.message || '加载 skill 详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function reloadSkills() {
  reloading.value = true
  try {
    await skillApi.reload()
    await loadSkills(selectedSkillName.value)
    ElMessage.success('Skills 已重新加载')
  } catch (error) {
    ElMessage.error(error.message || '重新加载 skills 失败')
  } finally {
    reloading.value = false
  }
}

function openCreateDialog() {
  editorMode.value = 'create'
  editingOriginalName.value = ''
  resetEditorForm()
  editorDialogVisible.value = true
}

function openEditDialog() {
  if (!skillDetail.value) {
    return
  }
  editorMode.value = 'edit'
  editingOriginalName.value = skillDetail.value.name
  editorForm.name = skillDetail.value.name || ''
  editorForm.title = skillDetail.value.title || ''
  editorForm.description = skillDetail.value.description || ''
  editorForm.toolName = skillDetail.value.toolName || ''
  editorForm.executorType = skillDetail.value.executorType || ''
  editorForm.debugArgumentsSchemaJson = skillDetail.value.debugArgumentsSchemaJson || ''
  editorForm.content = skillDetail.value.content || ''
  editorForm.overwrite = false
  editorDialogVisible.value = true
}

async function submitEditor() {
  if (!editorForm.name.trim()) {
    ElMessage.warning('请先填写 Skill 名称')
    return
  }
  if (editorForm.debugArgumentsSchemaJson.trim()) {
    try {
      parseSchemaDefinition(editorForm.debugArgumentsSchemaJson)
    } catch (error) {
      ElMessage.error('参数 Schema JSON 格式不正确')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      name: editorForm.name.trim(),
      title: editorForm.title.trim() || null,
      description: editorForm.description.trim() || null,
      toolName: editorForm.toolName.trim() || null,
      executorType: editorForm.executorType.trim() || null,
      debugQuestion: editorMode.value === 'edit' ? (skillDetail.value?.debugQuestion || null) : null,
      debugArgumentsJson: editorMode.value === 'edit' ? (skillDetail.value?.debugArgumentsJson || null) : null,
      debugArgumentsSchemaJson: editorForm.debugArgumentsSchemaJson.trim() || null,
      content: editorForm.content,
      overwrite: editorMode.value === 'create' ? editorForm.overwrite : true
    }
    const response = editorMode.value === 'create'
      ? await skillApi.create(payload)
      : await skillApi.update(editingOriginalName.value, payload)
    editorDialogVisible.value = false
    await loadSkills(response.data?.name || payload.name)
    ElMessage.success(editorMode.value === 'create' ? 'Skill 已创建' : 'Skill 已更新')
  } catch (error) {
    ElMessage.error(error.message || (editorMode.value === 'create' ? '创建 skill 失败' : '更新 skill 失败'))
  } finally {
    saving.value = false
  }
}

async function deleteCurrentSkill() {
  if (!skillDetail.value?.name) {
    return
  }
  await ElMessageBox.confirm(
    `确定删除 skill “${skillDetail.value.name}” 吗？这会删除对应目录下的 SKILL.md。`,
    '删除 Skill',
    {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    }
  )
  deleting.value = true
  try {
    const deletedName = skillDetail.value.name
    await skillApi.delete(deletedName)
    selectedSkillName.value = ''
    skillDetail.value = null
    await loadSkills('')
    ElMessage.success(`Skill ${deletedName} 已删除`)
  } catch (error) {
    ElMessage.error(error.message || '删除 skill 失败')
  } finally {
    deleting.value = false
  }
}

function handleImportSelect(uploadFile) {
  const file = uploadFile?.raw
  if (!file) {
    return
  }
  pendingImport.file = file
  pendingImport.fileName = file.name
  pendingImport.name = normalizeImportedName(file.name)
  pendingImport.overwrite = false
  importDialogVisible.value = true
}

function clearPendingImport() {
  importDialogVisible.value = false
  pendingImport.file = null
  pendingImport.fileName = ''
  pendingImport.name = ''
  pendingImport.overwrite = false
}

async function confirmImport() {
  if (!pendingImport.file) {
    ElMessage.warning('请先选择要导入的文件')
    return
  }
  importing.value = true
  try {
    const response = await skillApi.importFile({
      file: pendingImport.file,
      name: pendingImport.name.trim(),
      overwrite: pendingImport.overwrite
    })
    const created = response.data
    clearPendingImport()
    await loadSkills(created?.name || '')
    ElMessage.success('Skill 已导入')
  } catch (error) {
    ElMessage.error(error.message || '导入 skill 失败')
  } finally {
    importing.value = false
  }
}

function parseDebugArguments() {
  if (hasDebugSchema.value) {
    const result = {}
    for (const field of debugSchemaFields.value) {
      const coercedValue = coerceSchemaValue(field, debugSchemaValues[field.key])
      if (coercedValue !== null) {
        result[field.key] = coercedValue
      }
    }
    return result
  }
  const text = debugForm.argumentsText.trim()
  if (!text) {
    return {}
  }
  return JSON.parse(text)
}

function validateSchemaArguments() {
  if (!hasDebugSchema.value) {
    debugValidationErrors.value = {}
    return true
  }

  const nextErrors = {}
  for (const field of debugSchemaFields.value) {
    const normalizedValue = coerceSchemaValue(field, debugSchemaValues[field.key])
    const isEmptyString = field.type === 'string' && String(normalizedValue ?? '').trim() === ''
    const isMissingNumber = (field.type === 'number' || field.type === 'integer') && normalizedValue === null

    if (field.required) {
      if (field.type !== 'boolean' && (isEmptyString || isMissingNumber || normalizedValue === undefined || normalizedValue === null)) {
        nextErrors[field.key] = '请填写该参数'
        continue
      }
    }

    if (field.enumValues?.length && normalizedValue !== null && normalizedValue !== '' && !field.enumValues.includes(normalizedValue)) {
      nextErrors[field.key] = '参数值不在允许范围内'
      continue
    }

    if ((field.type === 'number' || field.type === 'integer') && normalizedValue !== null) {
      if (!Number.isFinite(normalizedValue)) {
        nextErrors[field.key] = '请输入有效数字'
        continue
      }
      if (field.type === 'integer' && !Number.isInteger(normalizedValue)) {
        nextErrors[field.key] = '请输入整数'
        continue
      }
      if (field.minimum !== undefined && normalizedValue < field.minimum) {
        nextErrors[field.key] = `不能小于 ${field.minimum}`
        continue
      }
      if (field.maximum !== undefined && normalizedValue > field.maximum) {
        nextErrors[field.key] = `不能大于 ${field.maximum}`
        continue
      }
    }

    if (field.type === 'string' && !isEmptyString) {
      const textValue = String(normalizedValue)
      if (field.minLength !== undefined && textValue.length < field.minLength) {
        nextErrors[field.key] = `至少输入 ${field.minLength} 个字符`
        continue
      }
      if (field.maxLength !== undefined && textValue.length > field.maxLength) {
        nextErrors[field.key] = `最多输入 ${field.maxLength} 个字符`
      }
    }
  }

  debugValidationErrors.value = nextErrors
  return Object.keys(nextErrors).length === 0
}

function formatDebugTrace(trace) {
  return JSON.stringify(trace || {}, null, 2)
}

async function runSkillDebug() {
  if (!skillDetail.value?.name) {
    return
  }
  if (!debugForm.question.trim()) {
    ElMessage.warning('请先填写调试问题')
    return
  }
  let argumentsPayload = {}
  try {
    if (hasDebugSchema.value && !validateSchemaArguments()) {
      ElMessage.warning('请先修正参数表单中的校验问题')
      return
    }
    argumentsPayload = parseDebugArguments()
  } catch (error) {
    ElMessage.error('参数 JSON 格式不正确')
    return
  }

  debugging.value = true
  try {
    const response = await skillApi.execute(skillDetail.value.name, {
      question: debugForm.question.trim(),
      arguments: argumentsPayload
    })
    debugResult.value = response.data || null
    ElMessage.success('Skill 调试执行完成')
  } catch (error) {
    ElMessage.error(error.message || '调试执行失败')
  } finally {
    debugging.value = false
  }
}

async function saveDebugSample() {
  if (!skillDetail.value?.name) {
    return
  }
  if (!debugForm.question.trim() && !debugForm.argumentsText.trim() && !hasDebugSchema.value) {
    ElMessage.warning('请先填写调试样例')
    return
  }

  let parsedArguments = {}
  try {
    if (hasDebugSchema.value && !validateSchemaArguments()) {
      ElMessage.warning('请先修正参数表单中的校验问题')
      return
    }
    parsedArguments = parseDebugArguments()
  } catch (error) {
    ElMessage.error('参数 JSON 格式不正确')
    return
  }

  savingDebugSample.value = true
  try {
    const response = await skillApi.update(skillDetail.value.name, {
      name: skillDetail.value.name,
      title: skillDetail.value.title || null,
      description: skillDetail.value.description || null,
      toolName: skillDetail.value.toolName || null,
      executorType: skillDetail.value.executorType || null,
      debugQuestion: debugForm.question.trim() || null,
      debugArgumentsJson: JSON.stringify(parsedArguments, null, 2),
      debugArgumentsSchemaJson: skillDetail.value.debugArgumentsSchemaJson || null,
      content: skillDetail.value.content || '',
      overwrite: true
    })
    skillDetail.value = response.data || skillDetail.value
    syncDebugFormFromDetail()
    ElMessage.success('调试样例已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存调试样例失败')
  } finally {
    savingDebugSample.value = false
  }
}

onMounted(async () => {
  try {
    await loadSkills()
  } catch (error) {
    ElMessage.error(error.message || '初始化 skill 页面失败')
  }
})
</script>

<style scoped>
.skill-page {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.skill-shell {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 16px;
  padding: 16px;
  border-radius: 24px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 250, 255, 0.98));
  border: 1px solid rgba(198, 212, 230, 0.55);
}

.skill-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(191, 210, 230, 0.42);
}

.toolbar-copy h1 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #24364d;
}

.toolbar-copy p {
  margin: 0;
  color: #6f8298;
  font-size: 13px;
}

.skill-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.skill-upload {
  display: inline-flex;
}

.skill-layout {
  min-height: 0;
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
}

.skill-list-panel,
.skill-detail-panel {
  min-height: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(194, 211, 228, 0.4);
  padding: 16px;
}

.skill-list-panel {
  display: flex;
  flex-direction: column;
}

.skill-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  color: #24364d;
  font-size: 13px;
  font-weight: 700;
}

.skill-list-header small {
  color: #7a8ca4;
  font-size: 11px;
}

.skill-search {
  margin-bottom: 12px;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
  contain: layout;
}

.skill-item {
  border: 1px solid rgba(209, 221, 235, 0.7);
  border-radius: 12px;
  background: rgba(247, 250, 255, 0.96);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
  content-visibility: auto;
  contain-intrinsic-size: 96px;
}

.skill-item.active {
  background: linear-gradient(135deg, rgba(225, 238, 255, 0.98), rgba(239, 246, 255, 0.96));
  border-color: rgba(180, 203, 230, 0.72);
}

.skill-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.skill-item strong {
  color: #24364d;
  font-size: 13px;
}

.skill-item span {
  color: #6f8298;
  font-size: 12px;
  line-height: 1.5;
}

.skill-item-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.skill-item code,
.skill-name-code,
.meta-card code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
}

.skill-item code {
  color: #5d738d;
  font-size: 11px;
  padding: 3px 6px;
  border-radius: 8px;
  background: rgba(236, 243, 250, 0.92);
}

.skill-empty,
.skill-detail-empty {
  display: grid;
  place-items: center;
  min-height: 180px;
  color: #8d9daf;
  font-size: 13px;
  text-align: center;
}

.skill-detail-panel {
  overflow: auto;
}

.skill-detail-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  position: sticky;
  top: 0;
  z-index: 2;
  padding-bottom: 12px;
  margin-bottom: 4px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(255, 255, 255, 0.88));
  backdrop-filter: blur(10px);
}

.detail-title-block {
  min-width: 0;
}

.detail-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.skill-detail-header h2 {
  margin: 0;
  font-size: 22px;
  color: #24364d;
}

.skill-detail-header p {
  margin: 0;
  color: #6f8298;
  font-size: 13px;
  line-height: 1.6;
}

.skill-name-code {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(236, 243, 250, 0.92);
  color: #5d738d;
  font-size: 11px;
}

.meta-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.meta-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(247, 250, 255, 0.96);
  border: 1px solid rgba(209, 221, 235, 0.7);
  content-visibility: auto;
  contain-intrinsic-size: 90px;
}

.meta-card span {
  color: #7a8ca4;
  font-size: 12px;
}

.meta-card code {
  color: #31465f;
  font-size: 11px;
  word-break: break-all;
}

.detail-actions {
  margin-top: 16px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.debug-panel {
  margin-top: 16px;
  padding: 16px;
  border-radius: 12px;
  background: rgba(247, 250, 255, 0.96);
  border: 1px solid rgba(209, 221, 235, 0.7);
  content-visibility: auto;
  contain-intrinsic-size: 360px;
}

.debug-panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.debug-panel-header strong {
  color: #24364d;
  font-size: 14px;
}

.debug-panel-header p {
  margin: 6px 0 0;
  color: #6f8298;
  font-size: 12px;
  line-height: 1.5;
}

.debug-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.debug-span-2 {
  grid-column: 1 / -1;
}

.schema-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.schema-label {
  color: #24364d;
  font-size: 12px;
  font-weight: 700;
}

.schema-description {
  color: #7a8ca4;
  font-size: 11px;
  line-height: 1.5;
}

.schema-tip {
  color: #8d7b4f;
  font-size: 11px;
  line-height: 1.5;
}

.schema-required {
  margin-left: 4px;
  color: #d14343;
}

.schema-control {
  width: 100%;
}

.schema-error {
  color: #d14343;
  font-size: 11px;
  line-height: 1.4;
}

.preview-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.debug-actions {
  margin-top: 10px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.debug-result {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.debug-result-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.debug-result-label {
  color: #7a8ca4;
  font-size: 12px;
  font-weight: 700;
}

.debug-result-box {
  margin: 0;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(209, 221, 235, 0.7);
  color: #31465f;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.skill-content {
  margin-top: 16px;
  content-visibility: auto;
  contain-intrinsic-size: 320px;
}

.skill-content pre {
  margin: 0;
  padding: 16px 18px;
  border-radius: 12px;
  background: rgba(247, 250, 255, 0.96);
  border: 1px solid rgba(209, 221, 235, 0.7);
  color: #31465f;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.editor-form {
  padding-top: 4px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.form-span-2 {
  grid-column: 1 / -1;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 1100px) {
  .skill-layout {
    grid-template-columns: 1fr;
  }

  .skill-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .skill-toolbar-actions {
    width: 100%;
  }

  .meta-grid,
  .form-grid,
  .debug-grid {
    grid-template-columns: 1fr;
  }

  .form-span-2,
  .debug-span-2 {
    grid-column: auto;
  }
}
</style>

