<template>
  <section class="kb-page">
    <div class="kb-shell glass-panel">
      <header class="kb-hero">
        <div class="kb-hero-copy">
          <span class="soft-chip">知识库管理中心</span>
          <h1>把每一个知识库变成一块清晰、可管理的业务资产面板</h1>
          <p>在这里创建知识库、查看文档规模和索引体量，并快速进入具体文档工作区。</p>
        </div>
        <el-button type="primary" size="large" @click="showCreateDialog = true">
          <el-icon><Plus /></el-icon>
          新建知识库
        </el-button>
      </header>

      <section class="kb-stats">
        <article v-for="card in statsCards" :key="card.label" class="stat-card">
          <div class="stat-icon" :style="{ background: card.bgColor, color: card.color }">
            <el-icon><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-copy">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.description }}</small>
          </div>
        </article>
      </section>

      <section class="kb-board glass-panel" v-loading="loading">
        <div class="kb-board-header">
          <div>
            <span class="section-heading">全部知识库</span>
            <h2>{{ knowledgeBases.length }} 个知识库正在服务你的问答系统</h2>
          </div>
          <span class="board-tip">点击卡片可直达文档管理页</span>
        </div>

        <div v-if="knowledgeBases.length" class="kb-grid">
          <article
            v-for="kb in knowledgeBases"
            :key="kb.id"
            class="kb-card"
            @click="goToDocuments(kb.id)"
          >
            <div class="kb-card-header">
              <div class="kb-card-icon">
                <el-icon><Folder /></el-icon>
              </div>

              <el-dropdown trigger="click" @command="(command) => handleCommand(command, kb)">
                <button class="more-btn" type="button" @click.stop>
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="view">
                      <el-icon><View /></el-icon>
                      查看文档
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided>
                      <el-icon><Delete /></el-icon>
                      删除知识库
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <div class="kb-card-body">
              <h3>{{ kb.name }}</h3>
              <p>{{ kb.description || '这个知识库还没有补充描述，你可以后续继续完善。' }}</p>
            </div>

            <div class="kb-card-metrics">
              <div class="metric-pill">
                <span>文档</span>
                <strong>{{ kb.documentCount || 0 }}</strong>
              </div>
              <div class="metric-pill">
                <span>切片</span>
                <strong>{{ kb.chunkCount || 0 }}</strong>
              </div>
            </div>

            <div class="kb-card-footer">
              <span>{{ formatDate(kb.createdAt) }}</span>
              <div class="kb-card-link">
                进入工作区
                <el-icon><ArrowRight /></el-icon>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="kb-empty">
          <div class="kb-empty-icon">
            <el-icon><Folder /></el-icon>
          </div>
          <h3>还没有知识库</h3>
          <p>先创建一个知识库，再把文档装进去，聊天页才能开始基于内容回答。</p>
          <el-button type="primary" @click="showCreateDialog = true">
            <el-icon><Plus /></el-icon>
            立即创建
          </el-button>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="showCreateDialog"
      title="创建知识库"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form :model="createForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input
            v-model="createForm.name"
            maxlength="128"
            show-word-limit
            placeholder="例如：项目文档、产品 FAQ、研发规范"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="6"
            :autosize="{ minRows: 4, maxRows: 12 }"
            maxlength="5000"
            show-word-limit
            placeholder="详细描述这个知识库覆盖的主题范围、适用对象、核心文档、业务规则与使用边界，后续检索和管理都会更清晰。"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建知识库</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, kbApi } from '@/api'
import {
  ArrowRight,
  ChatLineSquare,
  Delete,
  Document,
  Folder,
  MoreFilled,
  Plus,
  View
} from '@element-plus/icons-vue'

const router = useRouter()

const loading = ref(false)
const creating = ref(false)
const showCreateDialog = ref(false)
const knowledgeBases = ref([])

const createForm = ref({
  name: '',
  description: ''
})

const totalDocuments = computed(() => {
  return knowledgeBases.value.reduce((sum, kb) => sum + (kb.documentCount || 0), 0)
})

const totalChunks = computed(() => {
  return knowledgeBases.value.reduce((sum, kb) => sum + (kb.chunkCount || 0), 0)
})

const statsCards = computed(() => [
  {
    label: '知识库总数',
    value: knowledgeBases.value.length,
    description: '按主题组织你的业务内容',
    icon: Folder,
    color: '#4456f6',
    bgColor: 'rgba(91, 108, 255, 0.12)'
  },
  {
    label: '文档总量',
    value: totalDocuments.value,
    description: '已上传并可继续扩充的资料',
    icon: Document,
    color: '#119b7f',
    bgColor: 'rgba(30, 200, 165, 0.12)'
  },
  {
    label: '已生成切片',
    value: totalChunks.value,
    description: 'RAG 检索所依赖的语义颗粒度',
    icon: ChatLineSquare,
    color: '#d68a10',
    bgColor: 'rgba(255, 182, 72, 0.15)'
  }
])

function formatDate(value) {
  if (!value) return '刚刚创建'
  return new Date(value).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  })
}

async function fetchKnowledgeBases() {
  loading.value = true
  try {
    const response = await kbApi.list()
    const list = response.data || []

    for (const kb of list) {
      const docsResponse = await docApi.list(kb.id)
      const docs = docsResponse.data || []
      kb.documentCount = docs.length
      kb.chunkCount = docs.reduce((sum, item) => sum + (item.chunkCount || 0), 0)
    }

    knowledgeBases.value = list
  } catch (error) {
    ElMessage.error(error.message || '获取知识库列表失败')
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }

  creating.value = true
  try {
    await kbApi.create(createForm.value)
    ElMessage.success('知识库创建成功')
    showCreateDialog.value = false
    createForm.value = { name: '', description: '' }
    await fetchKnowledgeBases()
  } catch (error) {
    ElMessage.error(error.message || '创建知识库失败')
  } finally {
    creating.value = false
  }
}

function goToDocuments(kbId) {
  router.push({ path: `/kb/${kbId}/docs` })
}

async function handleCommand(command, kb) {
  if (command === 'view') {
    goToDocuments(kb.id)
    return
  }

  if (command === 'delete') {
    try {
      await ElMessageBox.confirm(
        `删除知识库「${kb.name}」会一并删除其文档与切片数据，确定继续吗？`,
        '删除知识库',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        }
      )
      await kbApi.delete(kb.id)
      ElMessage.success('知识库已删除')
      await fetchKnowledgeBases()
    } catch (error) {
      if (error !== 'cancel') {
        ElMessage.error(error.message || '删除知识库失败')
      }
    }
  }
}

onMounted(() => {
  fetchKnowledgeBases()
})
</script>

<style scoped>
.kb-page {
  height: 100%;
}

.kb-shell {
  height: 100%;
  border-radius: var(--radius-xl);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(246, 250, 255, 0.9));
}

.kb-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px;
  border-radius: 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(243, 248, 255, 0.96)),
    radial-gradient(circle at top left, rgba(123, 211, 255, 0.16), transparent 34%);
  border: 1px solid rgba(194, 211, 229, 0.44);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    0 16px 34px rgba(163, 183, 209, 0.14);
}

.kb-hero-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kb-hero h1 {
  max-width: 780px;
  font-size: 24px;
  line-height: 1.24;
  margin: 10px 0 8px;
  color: var(--text-primary);
}

.kb-hero p {
  max-width: 680px;
  color: var(--text-secondary);
  line-height: 1.6;
  font-size: 13px;
}

.kb-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(247, 250, 254, 0.96));
  border: 1px solid rgba(198, 214, 231, 0.52);
  box-shadow: var(--shadow-xs);
  transition:
    transform var(--transition-fast),
    box-shadow var(--transition-fast),
    border-color var(--transition-fast);
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: rgba(91, 108, 255, 0.2);
  box-shadow: var(--shadow-sm);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 18px;
  flex-shrink: 0;
}

.stat-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-copy span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.stat-copy strong {
  font-size: 20px;
  color: var(--text-primary);
}

.stat-copy small {
  color: var(--text-muted);
  line-height: 1.5;
}

.kb-board {
  flex: 1;
  min-height: 0;
  border-radius: 24px;
  padding: 18px;
  overflow: auto;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(244, 249, 254, 0.96));
  border: 1px solid rgba(194, 211, 229, 0.4);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 14px 30px rgba(165, 185, 210, 0.12);
}

.kb-board-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.kb-board-header h2 {
  margin-top: 8px;
  font-size: 18px;
  color: var(--text-primary);
}

.board-tip {
  color: var(--text-muted);
  font-size: 12px;
}

.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.kb-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 200px;
  padding: 18px;
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 250, 255, 0.96));
  border: 1px solid rgba(198, 214, 231, 0.58);
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.84),
    0 12px 24px rgba(168, 188, 212, 0.1);
  transition:
    transform var(--transition-fast),
    box-shadow var(--transition-fast),
    border-color var(--transition-fast);
}

.kb-card:hover {
  transform: translateY(-4px);
  border-color: rgba(91, 108, 255, 0.28);
  box-shadow:
    0 20px 36px rgba(156, 178, 204, 0.18),
    0 0 0 1px rgba(91, 108, 255, 0.08);
}

.kb-card-header,
.kb-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-card-icon,
.kb-empty-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(223, 238, 255, 0.98), rgba(236, 245, 255, 0.98));
  color: var(--primary-strong);
  font-size: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.68);
}

.more-btn {
  width: 32px;
  height: 32px;
  border: 1px solid rgba(203, 217, 234, 0.64);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--text-secondary);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition:
    background var(--transition-fast),
    color var(--transition-fast),
    border-color var(--transition-fast);
}

.more-btn:hover {
  background: #ffffff;
  color: var(--text-primary);
  border-color: rgba(91, 108, 255, 0.24);
}

.kb-card-body {
  flex: 1;
}

.kb-card-body h3 {
  font-size: 16px;
  margin-bottom: 6px;
  color: var(--text-primary);
}

.kb-card-body p {
  color: var(--text-secondary);
  line-height: 1.6;
  font-size: 12px;
}

.kb-card-metrics {
  display: flex;
  gap: 10px;
}

.metric-pill {
  flex: 1;
  padding: 10px 12px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(236, 243, 255, 0.96), rgba(245, 249, 255, 0.98));
  border: 1px solid rgba(201, 217, 235, 0.52);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric-pill span {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.metric-pill strong {
  font-size: 16px;
  color: var(--text-primary);
}

.kb-card-footer {
  color: var(--text-muted);
  font-size: 12px;
  padding-top: 2px;
  border-top: 1px solid rgba(227, 236, 246, 0.9);
}

.kb-card-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--primary-strong);
  font-weight: 800;
}

.kb-empty {
  min-height: 420px;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 20px;
  color: var(--text-secondary);
}

.kb-empty h3 {
  font-size: 24px;
  margin: 18px 0 10px;
  color: var(--text-primary);
}

.kb-empty p {
  max-width: 420px;
  margin: 0 auto 18px;
  color: var(--text-secondary);
  line-height: 1.7;
}

.kb-empty-icon {
  width: 58px;
  height: 58px;
  border-radius: 18px;
  font-size: 22px;
}

:deep(.el-dialog__body) {
  color: var(--text-primary);
}

:deep(.el-form-item__label) {
  color: var(--text-primary) !important;
  font-weight: 700;
}

@media (max-width: 1100px) {
  .kb-shell,
  .kb-board {
    padding: 14px;
  }

  .kb-hero,
  .kb-board-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .kb-stats {
    grid-template-columns: 1fr;
  }

  .kb-hero h1 {
    font-size: 20px;
  }

  .kb-board-header h2 {
    font-size: 16px;
  }
}
</style>
