<template>
  <aside class="sidebar glass-panel" :class="{ collapsed }">
    <div class="sidebar-header">
      <div class="brand" :class="{ centered: collapsed }">
        <div class="brand-mark">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div v-if="!collapsed" class="brand-copy">
          <span class="brand-title">智能问答 Ragent</span>
          <span class="brand-subtitle">Knowledge Assistant Workspace</span>
        </div>
      </div>

      <button
        class="collapse-btn"
        type="button"
        :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="$emit('toggle')"
      >
        <el-icon v-if="collapsed"><Expand /></el-icon>
        <el-icon v-else><Fold /></el-icon>
      </button>
    </div>

    <button class="new-chat-btn" :class="{ compact: collapsed }" type="button" @click="createNewChat">
      <el-icon><Plus /></el-icon>
      <span v-if="!collapsed">新建对话</span>
    </button>

    <nav class="nav-block">
      <span v-if="!collapsed" class="section-label">工作区导航</span>
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path), compact: collapsed }"
        :title="collapsed ? item.label : ''"
      >
        <div class="nav-item-icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div v-if="!collapsed" class="nav-item-copy">
          <span class="nav-item-title">{{ item.label }}</span>
          <span class="nav-item-desc">{{ item.description }}</span>
        </div>
      </router-link>
    </nav>

    <section v-if="!collapsed" class="history-block">
      <div class="history-header">
        <div>
          <span class="section-label">最近会话</span>
          <p class="history-caption">切换后自动恢复消息记录</p>
        </div>
        <button
          v-if="recentSessions.length"
          class="clear-btn"
          type="button"
          @click="handleClearHistory"
        >
          清空
        </button>
      </div>

      <div v-if="recentSessions.length" class="history-list">
        <div
          v-for="session in recentSessions"
          :key="session.id"
          class="history-item"
          :class="{ active: currentSessionId === session.id }"
          @click="selectSession(session.id)"
        >
          <div class="history-item-top">
            <span class="history-title">{{ session.title }}</span>
            <span class="history-time">{{ formatTime(session.updatedAt) }}</span>
          </div>
          <span class="history-preview">{{ session.preview || '继续查看这段对话内容' }}</span>
          <div class="history-item-footer">
            <span v-if="session.kbName" class="history-tag">{{ session.kbName }}</span>
            <button
              type="button"
              class="history-delete-btn"
              title="删除会话"
              @click.stop="handleDeleteSession(session.id)"
            >
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </div>
      </div>

      <div v-else class="history-empty">
        <div class="history-empty-icon">
          <el-icon><ChatLineSquare /></el-icon>
        </div>
        <h3>还没有历史会话</h3>
        <p>发起一条新问题后，这里会自动出现可继续的对话记录。</p>
      </div>
    </section>

    <div class="sidebar-footer" :class="{ compact: collapsed }">
      <div class="footer-card">
        <div class="footer-avatar">
          <el-icon><User /></el-icon>
        </div>
        <div v-if="!collapsed" class="footer-copy">
          <span class="footer-name">当前工作区</span>
          <span class="footer-role">轻量 RAG 助手界面</span>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, Connection, Delete, Expand, Fold, Folder, Plus, Reading, User } from '@element-plus/icons-vue'
import { chatApi } from '@/api'
import { clearRecentSessions, listRecentSessions, removeRecentSession, subscribeRecentSessions } from '@/utils/chatSessions'

defineProps({
  collapsed: {
    type: Boolean,
    default: false
  }
})

defineEmits(['toggle'])

const route = useRoute()
const router = useRouter()
const recentSessions = ref([])

const navItems = [
  {
    path: '/',
    label: '智能问答',
    description: '面向知识库的检索问答',
    icon: ChatLineSquare
  },
  {
    path: '/kb',
    label: '知识库管理',
    description: '查看库、文档与统计信息',
    icon: Folder
  },
  {
    path: '/mcp',
    label: 'MCP 管理',
    description: '查看服务、工具和连接状态',
    icon: Connection
  },
  {
    path: '/skills',
    label: 'Skill 管理',
    description: '查看本地 SKILL.md 与重载状态',
    icon: Reading
  }
]

const currentSessionId = computed(() => {
  return typeof route.query.session === 'string' ? route.query.session : ''
})

const isActive = (path) => {
  if (path === '/') {
    return route.path === '/'
  }
  return route.path.startsWith(path)
}

const refreshSessions = async () => {
  try {
    const response = await chatApi.listSessions()
    recentSessions.value = (response.data || []).map((s) => ({
      id: s.sessionId,
      title: s.title || '未命名会话',
      preview: s.preview || '',
      kbId: s.kbId,
      kbName: s.kbName || '',
      messageCount: s.messageCount || 0,
      updatedAt: s.lastActivityAt || ''
    }))
  } catch {
    recentSessions.value = listRecentSessions()
  }
}

const createNewChat = () => {
  router.push({ path: '/' })
}

const selectSession = (sessionId) => {
  router.push({ path: '/', query: { session: sessionId } })
}

const handleClearHistory = async () => {
  await ElMessageBox.confirm(
    '清空最近会话列表不会删除数据库消息，只会移除侧栏快捷入口。确定继续吗？',
    '清空最近会话',
    {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning'
    }
  )
  clearRecentSessions()
  refreshSessions()
}

const handleDeleteSession = async (sessionId) => {
  try {
    await ElMessageBox.confirm(
      '删除该会话将同时清除数据库中的所有消息记录，此操作不可撤销。确定继续吗？',
      '删除会话',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  try {
    await chatApi.deleteSession(sessionId)
    removeRecentSession(sessionId)
    ElMessage.success('会话已删除')
    if (currentSessionId.value === sessionId) {
      router.push({ path: '/' })
    }
    refreshSessions()
  } catch (error) {
    ElMessage.error(error.message || '删除会话失败')
  }
}

const formatTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

let unsubscribe = null

onMounted(() => {
  refreshSessions()
  unsubscribe = subscribeRecentSessions(refreshSessions)
})

onUnmounted(() => {
  if (unsubscribe) {
    unsubscribe()
  }
})
</script>

<style scoped>
.sidebar {
  position: fixed;
  top: var(--shell-gap);
  left: var(--shell-gap);
  z-index: 10;
  width: var(--sidebar-width);
  height: calc(100dvh - (var(--shell-gap) * 2));
  min-height: 0;
  border-radius: 28px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  overflow: auto;
  overscroll-behavior: contain;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.94) 0%, rgba(246, 250, 255, 0.98) 100%);
  border: 1px solid rgba(198, 212, 230, 0.55);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 20px 44px rgba(164, 183, 210, 0.16);
  transition:
    width var(--transition-normal),
    padding var(--transition-normal),
    box-shadow var(--transition-fast);
}

.sidebar.collapsed {
  width: 84px;
  padding: 14px 10px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.brand.centered {
  justify-content: center;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #8ccfff, #7e8fff);
  box-shadow: 0 12px 24px rgba(141, 166, 210, 0.22);
  flex-shrink: 0;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.brand-title {
  color: #24364d;
  font-size: 14px;
  font-weight: 800;
}

.brand-subtitle {
  color: #8293a8;
  font-size: 10px;
}

.collapse-btn {
  width: 34px;
  height: 34px;
  border: 1px solid rgba(201, 215, 233, 0.7);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.86);
  color: #607792;
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--transition-fast), color var(--transition-fast), transform var(--transition-fast);
}

.collapse-btn:hover {
  background: #ffffff;
  color: #3e5875;
  transform: translateY(-1px);
}

.new-chat-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  margin-top: 14px;
  border: 1px solid rgba(192, 209, 229, 0.68);
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(236, 245, 255, 0.96), rgba(223, 237, 255, 0.94));
  color: #325a86;
  font-weight: 700;
  cursor: pointer;
  transition: transform var(--transition-fast), background var(--transition-fast), box-shadow var(--transition-fast);
}

.new-chat-btn:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, rgba(240, 247, 255, 1), rgba(229, 241, 255, 0.98));
  box-shadow: 0 14px 24px rgba(176, 196, 222, 0.18);
}

.new-chat-btn.compact {
  min-height: 44px;
  padding: 0;
}

.nav-block,
.history-block {
  margin-top: 16px;
}

.section-label {
  display: inline-block;
  color: #99a8b8;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  margin-bottom: 12px;
}

.nav-block {
  display: flex;
  flex-direction: column;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 16px;
  margin-bottom: 8px;
  transition: background var(--transition-fast), transform var(--transition-fast), border-color var(--transition-fast);
  border: 1px solid transparent;
}

.nav-item.compact {
  justify-content: center;
  padding: 10px 0;
}

.nav-item:hover {
  background: rgba(234, 242, 251, 0.88);
  transform: translateX(2px);
}

.nav-item.active {
  background: linear-gradient(135deg, rgba(225, 238, 255, 0.98), rgba(239, 246, 255, 0.96));
  border-color: rgba(179, 203, 230, 0.62);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.42);
}

.nav-item-icon {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(233, 241, 251, 0.96);
  color: #6280a0;
  flex-shrink: 0;
}

.nav-item.active .nav-item-icon {
  background: linear-gradient(135deg, #bfe4ff, #b9d4ff);
  color: #325b89;
}

.nav-item-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.nav-item-title {
  color: #24364d;
  font-size: 13px;
  font-weight: 700;
}

.nav-item-desc {
  color: #8697aa;
  font-size: 11px;
}

.history-block {
  display: flex;
  flex-direction: column;
}

.history-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.history-caption {
  color: #98a7b8;
  font-size: 12px;
}

.clear-btn {
  border: 0;
  background: transparent;
  color: #8093a8;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.history-list {
  overflow: visible;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 2px;
}

.history-item {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  width: 100%;
  border: 1px solid rgba(227, 236, 246, 0.9);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.88);
  padding: 12px;
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast), border-color var(--transition-fast), transform var(--transition-fast), box-shadow var(--transition-fast);
}

.history-item:hover {
  background: rgba(255, 255, 255, 0.98);
  transform: translateY(-1px);
  box-shadow: 0 10px 18px rgba(181, 198, 221, 0.12);
}

.history-item.active {
  background: linear-gradient(135deg, rgba(235, 244, 255, 0.98), rgba(246, 250, 255, 0.98));
  border-color: rgba(180, 203, 230, 0.72);
}

.history-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-title {
  color: #24364d;
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-time,
.history-preview {
  color: #8798aa;
  font-size: 12px;
}

.history-time {
  white-space: nowrap;
  flex-shrink: 0;
}

.history-preview {
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-tag {
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: rgba(228, 240, 255, 0.96);
  color: #5f7ea2;
  font-size: 11px;
  font-weight: 700;
}

.history-item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.history-delete-btn {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #a0aec0;
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}

.history-item:hover .history-delete-btn {
  opacity: 1;
}

.history-delete-btn:hover {
  background: rgba(255, 200, 200, 0.92);
  color: #e53e3e;
}

.history-empty {
  flex: 1;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 12px;
  color: #8d9daf;
}

.history-empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: rgba(235, 242, 250, 0.96);
  color: #6887a7;
}

.history-empty h3 {
  font-size: 15px;
  color: #24364d;
  margin-bottom: 8px;
}

.history-empty p {
  font-size: 12px;
  line-height: 1.6;
}

.sidebar-footer {
  padding-top: 16px;
}

.sidebar-footer.compact {
  padding-top: 14px;
}

.footer-card {
  display: flex;
  align-items: center;
  gap: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(225, 236, 246, 0.88);
  padding: 12px;
}

.sidebar-footer.compact .footer-card {
  justify-content: center;
  padding: 10px 0;
}

.footer-avatar {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(224, 238, 255, 0.98), rgba(213, 231, 255, 0.98));
  color: #6080a3;
}

.footer-copy {
  display: flex;
  flex-direction: column;
}

.footer-name {
  color: #24364d;
  font-size: 12px;
  font-weight: 700;
}

.footer-role {
  color: #8b9caf;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .sidebar,
  .sidebar.collapsed {
    position: relative;
    top: 0;
    left: 0;
    width: 100%;
    height: auto;
    max-height: none;
  }

  .history-block {
    max-height: 280px;
  }
}
</style>
