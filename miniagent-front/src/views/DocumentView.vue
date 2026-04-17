<template>
  <section class="doc-page">
    <div class="doc-shell glass-panel">
      <header class="doc-hero">
        <div class="doc-hero-left">
          <button type="button" class="back-btn" @click="goBack">
            <el-icon><ArrowLeft /></el-icon>
          </button>

          <div class="doc-hero-copy">
            <span class="soft-chip">文档管理</span>
            <h1>{{ kbName }}</h1>
            <p>在这里集中上传、索引和清理文档，让知识库始终保持可用和整洁。</p>
          </div>
        </div>

        <div class="doc-hero-actions">
          <el-button type="primary" size="large" @click="handleUpload">
            <el-icon><Upload /></el-icon>
            上传文档
          </el-button>
          <input
            ref="fileInput"
            type="file"
            hidden
            accept=".pdf,.txt,.md,.doc,.docx"
            @change="onFileSelected"
          />
        </div>
      </header>

      <section
        class="upload-zone"
        :class="{ dragging: isDragging }"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="onFileDrop"
        @click="handleUpload"
      >
        <div class="upload-zone-main">
          <div class="upload-zone-icon">
            <el-icon><Upload /></el-icon>
          </div>
          <div>
            <h2>拖拽文档到这里，或者点击选择文件</h2>
            <p>支持 PDF、TXT、MD、DOC、DOCX。上传后你可以继续执行索引，马上让聊天页可检索。</p>
          </div>
        </div>
        <div class="upload-zone-tags">
          <span v-for="type in ['PDF', 'TXT', 'MD', 'DOC', 'DOCX']" :key="type">{{ type }}</span>
        </div>
      </section>

      <section class="doc-table-panel glass-panel">
        <div class="doc-table-header">
          <div>
            <span class="section-heading">文档列表</span>
            <h2>{{ documents.length }} 份文档</h2>
          </div>
          <el-input
            v-model="searchQuery"
            placeholder="搜索文档名"
            clearable
            class="search-input"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>

        <div class="table-wrap" v-loading="loading">
          <el-table
            v-if="filteredDocuments.length"
            :data="filteredDocuments"
            style="width: 100%"
          >
            <el-table-column label="文档" min-width="280">
              <template #default="{ row }">
                <div class="doc-name-cell">
                  <div class="doc-file-icon" :style="getFileIconStyle(row.fileType)">
                    <el-icon><Document /></el-icon>
                  </div>
                  <div class="doc-name-copy">
                    <strong>{{ row.name }}</strong>
                    <span>{{ (row.fileType || 'unknown').toUpperCase() }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <span class="status-pill" :class="getStatusClass(row.status)">
                  <i></i>
                  {{ formatStatus(row.status) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="切片数" width="100" align="center">
              <template #default="{ row }">
                <strong>{{ row.chunkCount || 0 }}</strong>
              </template>
            </el-table-column>

            <el-table-column label="创建时间" width="160">
              <template #default="{ row }">
                <span class="date-text">{{ formatDate(row.createdAt) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <div class="action-buttons">
                  <button
                    type="button"
                    class="table-btn primary"
                    :disabled="row.status === 'INDEXING' || row.status === 'INDEXED'"
                    :class="{ loading: indexingDocId === row.id }"
                    @click="handleIndex(row.id)"
                  >
                    <el-icon><Refresh /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="table-btn danger"
                    @click="handleDelete(row.id, row.name)"
                  >
                    <el-icon><Delete /></el-icon>
                  </button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <div v-else class="doc-empty">
            <div class="doc-empty-icon">
              <el-icon><Document /></el-icon>
            </div>
            <h3>{{ documents.length ? '没有匹配结果' : '还没有文档' }}</h3>
            <p>
              {{ documents.length ? '换个关键词试试，或者清空搜索条件。' : '上传第一份文档后，就可以在这里进行索引和删除操作。' }}
            </p>
          </div>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="showUploadProgress"
      title="上传文档"
      width="420px"
      :close-on-click-modal="false"
      :show-close="false"
    >
      <div class="upload-progress">
        <el-progress
          type="circle"
          :percentage="uploadProgress"
          :status="uploadProgress === 100 ? 'success' : undefined"
          :width="110"
        />
        <p>{{ uploadStatus }}</p>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, kbApi } from '@/api'
import {
  ArrowLeft,
  Delete,
  Document,
  Refresh,
  Search,
  Upload
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const kbId = computed(() => route.params.kbId)
const kbName = ref('知识库')
const loading = ref(false)
const indexingDocId = ref(null)
const searchQuery = ref('')
const isDragging = ref(false)
const documents = ref([])
const showUploadProgress = ref(false)
const uploadProgress = ref(0)
const uploadStatus = ref('上传中...')
const fileInput = ref(null)

const filteredDocuments = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) {
    return documents.value
  }
  return documents.value.filter((item) => item.name.toLowerCase().includes(keyword))
})

function getFileIconStyle(fileType) {
  const palette = {
    pdf: { background: 'rgba(244, 95, 122, 0.12)', color: '#f45f7a' },
    txt: { background: 'rgba(77, 141, 255, 0.12)', color: '#4d8dff' },
    md: { background: 'rgba(30, 200, 165, 0.12)', color: '#119b7f' },
    doc: { background: 'rgba(91, 108, 255, 0.12)', color: '#4456f6' },
    docx: { background: 'rgba(91, 108, 255, 0.12)', color: '#4456f6' }
  }
  return palette[fileType?.toLowerCase()] || { background: 'rgba(17, 24, 39, 0.06)', color: '#52607a' }
}

function getStatusClass(status) {
  return {
    UPLOADED: 'info',
    PARSED: 'warning',
    INDEXING: 'warning',
    INDEXED: 'success',
    FAILED: 'danger'
  }[status] || 'info'
}

function formatStatus(status) {
  return {
    UPLOADED: '已上传',
    PARSED: '已解析',
    INDEXING: '索引中',
    INDEXED: '已索引',
    FAILED: '失败'
  }[status] || status
}

function formatDate(value) {
  if (!value) return '刚刚'
  return new Date(value).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
}

function goBack() {
  router.push('/kb')
}

async function fetchKbInfo() {
  const response = await kbApi.list()
  const target = (response.data || []).find((item) => String(item.id) === String(kbId.value))
  if (target) {
    kbName.value = target.name
  }
}

async function fetchDocuments() {
  loading.value = true
  try {
    const response = await docApi.list(kbId.value)
    documents.value = response.data || []
  } catch (error) {
    ElMessage.error(error.message || '获取文档列表失败')
  } finally {
    loading.value = false
  }
}

function handleUpload() {
  fileInput.value?.click()
}

function onFileSelected(event) {
  const file = event.target.files?.[0]
  if (file) {
    uploadFile(file)
  }
  event.target.value = ''
}

function onFileDrop(event) {
  isDragging.value = false
  const file = event.dataTransfer.files?.[0]
  if (file) {
    uploadFile(file)
  }
}

async function uploadFile(file) {
  showUploadProgress.value = true
  uploadProgress.value = 8
  uploadStatus.value = '上传中...'

  let progressTimer = null

  try {
    progressTimer = setInterval(() => {
      if (uploadProgress.value < 88) {
        uploadProgress.value += 8
      }
    }, 180)

    await docApi.upload(kbId.value, file)
    clearInterval(progressTimer)
    uploadProgress.value = 100
    uploadStatus.value = '上传完成，正在刷新列表'
    await fetchDocuments()
    ElMessage.success('文档上传成功')
  } catch (error) {
    if (progressTimer) clearInterval(progressTimer)
    ElMessage.error(error.message || '文档上传失败')
  } finally {
    setTimeout(() => {
      showUploadProgress.value = false
    }, 500)
  }
}

async function handleIndex(docId) {
  indexingDocId.value = docId
  try {
    await docApi.index(docId)
    ElMessage.success('已开始索引文档')
    await pollDocumentStatus(docId)
  } catch (error) {
    indexingDocId.value = null
    ElMessage.error(error.message || '启动索引失败')
  }
}

async function pollDocumentStatus(docId) {
  let attempts = 0
  const maxAttempts = 30

  while (attempts < maxAttempts) {
    const response = await docApi.list(kbId.value)
    const target = (response.data || []).find((item) => item.id === docId)
    documents.value = response.data || []

    if (target && target.status !== 'INDEXING') {
      indexingDocId.value = null
      if (target.status === 'INDEXED') {
        ElMessage.success('文档索引成功')
      } else if (target.status === 'FAILED') {
        ElMessage.error('文档索引失败')
      }
      return
    }

    attempts += 1
    await new Promise((resolve) => setTimeout(resolve, 2000))
  }

  indexingDocId.value = null
}

async function handleDelete(docId, docName) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${docName || '未命名文档'}」吗？此操作不可撤销。`,
      '删除文档',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
    await docApi.delete(docId)
    ElMessage.success('文档已删除')
    await fetchDocuments()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message || '删除文档失败')
    }
  }
}

onMounted(async () => {
  try {
    await Promise.all([fetchKbInfo(), fetchDocuments()])
  } catch (error) {
    ElMessage.error(error.message || '初始化文档页失败')
  }
})
</script>

<style scoped>
.doc-page {
  height: 100%;
}

.doc-shell {
  height: 100%;
  border-radius: var(--radius-xl);
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.doc-hero,
.upload-zone,
.doc-table-panel {
  border-radius: 28px;
}

.doc-hero {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 24px 28px;
  background: linear-gradient(135deg, rgba(28, 37, 59, 0.94), rgba(20, 28, 46, 0.88));
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.doc-hero-left {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.back-btn {
  width: 46px;
  height: 46px;
  border: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.06);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.doc-hero-copy h1 {
  margin: 14px 0 8px;
  font-size: 34px;
}

.doc-hero-copy p {
  color: var(--text-secondary);
  line-height: 1.7;
}

.upload-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 24px 28px;
  border: 1.5px dashed rgba(91, 108, 255, 0.24);
  background:
    linear-gradient(135deg, rgba(91, 108, 255, 0.08), rgba(30, 200, 165, 0.06)),
    rgba(255, 255, 255, 0.04);
  cursor: pointer;
  transition: transform var(--transition-fast), box-shadow var(--transition-fast), border-color var(--transition-fast);
}

.upload-zone:hover,
.upload-zone.dragging {
  transform: translateY(-2px);
  border-color: rgba(91, 108, 255, 0.42);
  box-shadow: var(--shadow-glow-soft);
}

.upload-zone-main {
  display: flex;
  align-items: center;
  gap: 16px;
}

.upload-zone-icon {
  width: 68px;
  height: 68px;
  border-radius: 22px;
  display: grid;
  place-items: center;
  background: rgba(91, 108, 255, 0.14);
  color: var(--primary-strong);
  font-size: 28px;
}

.upload-zone h2 {
  margin-bottom: 8px;
  font-size: 24px;
}

.upload-zone p {
  color: var(--text-secondary);
}

.upload-zone-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.upload-zone-tags span {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 800;
}

.doc-table-panel {
  flex: 1;
  min-height: 0;
  padding: 20px;
}

.doc-table-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.doc-table-header h2 {
  margin-top: 8px;
  font-size: 24px;
}

.search-input {
  width: 260px;
}

.table-wrap {
  min-height: 320px;
}

.doc-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.doc-file-icon,
.doc-empty-icon {
  width: 46px;
  height: 46px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 20px;
}

.doc-name-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.doc-name-copy span,
.date-text {
  color: var(--text-muted);
  font-size: 12px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.status-pill i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-pill.success {
  background: rgba(30, 200, 165, 0.16);
  color: #119b7f;
}

.status-pill.success i {
  background: #119b7f;
}

.status-pill.warning {
  background: rgba(255, 182, 72, 0.2);
  color: #d68a10;
}

.status-pill.warning i {
  background: #d68a10;
}

.status-pill.info {
  background: rgba(77, 141, 255, 0.18);
  color: #4d8dff;
}

.status-pill.info i {
  background: #4d8dff;
}

.status-pill.danger {
  background: rgba(244, 95, 122, 0.16);
  color: #f45f7a;
}

.status-pill.danger i {
  background: #f45f7a;
}

.action-buttons {
  display: inline-flex;
  gap: 8px;
}

.table-btn {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 14px;
  display: grid;
  place-items: center;
  cursor: pointer;
}

.table-btn.primary {
  background: rgba(91, 108, 255, 0.16);
  color: var(--primary-strong);
}

.table-btn.primary.loading {
  animation: spin 1s linear infinite;
}

.table-btn.primary:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.table-btn.danger {
  background: rgba(244, 95, 122, 0.16);
  color: #f45f7a;
}

.doc-empty {
  min-height: 320px;
  display: grid;
  place-items: center;
  text-align: center;
}

.doc-empty h3 {
  margin: 18px 0 10px;
  font-size: 24px;
}

.doc-empty p {
  max-width: 380px;
  color: var(--text-secondary);
}

.upload-progress {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 12px 0 20px;
}

@keyframes spin {
  from {
    transform: rotate(0);
  }
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1100px) {
  .doc-shell,
  .doc-table-panel {
    padding: 14px;
  }

  .doc-hero,
  .doc-table-header,
  .upload-zone {
    flex-direction: column;
    align-items: flex-start;
  }

  .search-input {
    width: 100%;
  }
}
</style>
