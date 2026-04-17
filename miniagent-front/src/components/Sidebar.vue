<template>
  <aside class="sidebar glass-panel">
    <div class="sidebar-header">
      <div class="brand">
        <div class="brand-mark">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="brand-copy">
          <span class="brand-title">智能问答 Ragent</span>
          <span class="brand-subtitle">Knowledge Assistant Workspace</span>
        </div>
      </div>

      <button class="new-chat-btn" type="button" @click="createNewChat">
        <el-icon><Plus /></el-icon>
        <span>新建对话</span>
      </button>
    </div>

    <nav class="nav-block">
      <span class="section-label">工作区导航</span>
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
      >
        <div class="nav-item-icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div class="nav-item-copy">
          <span class="nav-item-title">{{ item.label }}</span>
          <span class="nav-item-desc">{{ item.description }}</span>
        </div>
      </router-link>
    </nav>

    <section class="history-block">
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
        <button
          v-for="session in recentSessions"
          :key="session.id"
          type="button"
          class="history-item"
          :class="{ active: currentSessionId === session.id }"
          @click="selectSession(session.id)"
        >
          <div class="history-item-top">
            <span class="history-title">{{ session.title }}</span>
            <span class="history-time">{{ formatTime(session.updatedAt) }}</span>
          </div>
          <span class="history-preview">{{ session.preview || '继续查看这段对话内容' }}</span>
          <span v-if="session.kbName" class="history-tag">{{ session.kbName }}</span>
        </button>
      </div>

      <div v-else class="history-empty">
        <div class="history-empty-icon">
          <el-icon><ChatLineSquare /></el-icon>
        </div>
        <h3>还没有历史会话</h3>
        <p>发起一条新问题后，这里会自动出现可继续的对话记录。</p>
      </div>
    </section>

    <div class="sidebar-footer">
      <div class="footer-card">
        <div class="footer-avatar">
          <el-icon><User /></el-icon>
        </div>
        <div class="footer-copy">
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
import { ElMessageBox } from 'element-plus'
import { ChatLineSquare, Folder, Plus, User } from '@element-plus/icons-vue'
import { clearRecentSessions, listRecentSessions, subscribeRecentSessions } from '@/utils/chatSessions'

const route = useRoute()
const router = useRouter()
const recentSessions = ref(listRecentSessions())

const navItems = [
  {
    path: '/',
    label: '智能问答',
    description: '面向知识库的检索问答',
    icon: 'ChatLineSquare'
  },
  {
    path: '/kb',
    label: '知识库管理',
    description: '查看库、文档与统计信息',
    icon: 'Folder'
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

const refreshSessions = () => {
  recentSessions.value = listRecentSessions()
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
  border-radius: var(--radius-xl);
  padding: 14px;
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(180deg, rgba(15, 23, 42, 0.92) 0%, rgba(17, 24, 39, 0.96) 100%);
  border-color: rgba(255, 255, 255, 0.08);
  box-shadow: 0 30px 70px rgba(2, 6, 23, 0.36);
}

.sidebar-header {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.95), rgba(129, 140, 248, 0.78));
  box-shadow: 0 16px 34px rgba(91, 108, 255, 0.35);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.brand-title {
  color: var(--text-inverse);
  font-size: 14px;
  font-weight: 800;
}

.brand-subtitle {
  color: rgba(226, 232, 240, 0.66);
  font-size: 10px;
}

.new-chat-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.24), rgba(91, 108, 255, 0.12));
  color: var(--text-inverse);
  font-weight: 700;
  cursor: pointer;
  transition: transform var(--transition-fast), background var(--transition-fast), box-shadow var(--transition-fast);
}

.new-chat-btn:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.34), rgba(91, 108, 255, 0.18));
  box-shadow: 0 16px 30px rgba(91, 108, 255, 0.18);
}

.nav-block,
.history-block {
  margin-top: 16px;
}

.section-label {
  display: inline-block;
  color: rgba(203, 213, 225, 0.58);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
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
  border-radius: 14px;
  margin-bottom: 8px;
  transition: background var(--transition-fast), transform var(--transition-fast), border-color var(--transition-fast);
  border: 1px solid transparent;
}

.nav-item:hover {
  background: var(--bg-sidebar-hover);
  transform: translateX(2px);
}

.nav-item.active {
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.22), rgba(91, 108, 255, 0.08));
  border-color: rgba(91, 108, 255, 0.22);
  box-shadow: inset 0 0 0 1px rgba(91, 108, 255, 0.08);
}

.nav-item-icon {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.07);
  color: #dde7ff;
  flex-shrink: 0;
}

.nav-item.active .nav-item-icon {
  background: rgba(91, 108, 255, 0.24);
  color: #ffffff;
}

.nav-item-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.nav-item-title {
  color: #f8fbff;
  font-size: 13px;
  font-weight: 700;
}

.nav-item-desc {
  color: rgba(226, 232, 240, 0.6);
  font-size: 11px;
}

.history-block {
  flex: 1;
  min-height: 0;
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
  color: rgba(148, 163, 184, 0.55);
  font-size: 12px;
}

.clear-btn {
  border: 0;
  background: transparent;
  color: rgba(203, 213, 225, 0.72);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.history-list {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
}

.history-item {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  width: 100%;
  border: 1px solid transparent;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
  padding: 12px;
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast), border-color var(--transition-fast), transform var(--transition-fast);
}

.history-item:hover {
  background: rgba(255, 255, 255, 0.08);
  transform: translateY(-1px);
}

.history-item.active {
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.22), rgba(30, 200, 165, 0.09));
  border-color: rgba(91, 108, 255, 0.24);
}

.history-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-title {
  color: #f8fbff;
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-time,
.history-preview {
  color: rgba(226, 232, 240, 0.6);
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
  background: rgba(255, 255, 255, 0.08);
  color: #dbe6ff;
  font-size: 11px;
  font-weight: 700;
}

.history-empty {
  flex: 1;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 12px;
  color: rgba(226, 232, 240, 0.72);
}

.history-empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.08);
}

.history-empty h3 {
  font-size: 15px;
  color: #f8fbff;
  margin-bottom: 8px;
}

.history-empty p {
  font-size: 12px;
  line-height: 1.6;
}

.sidebar-footer {
  padding-top: 16px;
}

.footer-card {
  display: flex;
  align-items: center;
  gap: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.05);
  padding: 12px;
}

.footer-avatar {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(91, 108, 255, 0.32), rgba(30, 200, 165, 0.2));
  color: #f8fbff;
}

.footer-copy {
  display: flex;
  flex-direction: column;
}

.footer-name {
  color: #f8fbff;
  font-size: 12px;
  font-weight: 700;
}

.footer-role {
  color: rgba(226, 232, 240, 0.58);
  font-size: 11px;
}

@media (max-width: 1100px) {
  .sidebar {
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
