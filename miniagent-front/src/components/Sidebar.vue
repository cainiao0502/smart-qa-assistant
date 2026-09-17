<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="sidebar-header">
      <div class="brand" :class="{ centered: collapsed }">
        <div class="brand-mark" aria-hidden="true">问</div>
        <div v-show="!collapsed" class="brand-copy">
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
      <span v-show="!collapsed">新建对话</span>
    </button>

    <nav class="nav-block">
      <span v-show="!collapsed" class="section-label">工作区导航</span>
      <router-link
        v-for="item in visibleNavItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path), compact: collapsed }"
        :title="collapsed ? item.label : ''"
      >
        <div class="nav-item-icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div v-show="!collapsed" class="nav-item-copy">
          <span class="nav-item-title">{{ item.label }}</span>
          <span class="nav-item-desc">{{ item.description }}</span>
        </div>
      </router-link>
    </nav>

    <section v-show="!collapsed" class="history-block">
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
          :title="session.kbName ? `${session.kbName} · ${session.title}` : session.title"
          @click="selectSession(session.id)"
        >
          <span v-if="session.kbName" class="history-dot" aria-hidden="true"></span>
          <span class="history-title">{{ session.title }}</span>
          <span class="history-time">{{ formatTime(session.updatedAt) }}</span>
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

      <div v-else class="history-empty">
        <div class="history-empty-icon">
          <el-icon><ChatLineSquare /></el-icon>
        </div>
        <h3>还没有历史会话</h3>
        <p>发起一条新问题后，这里会自动出现可继续的对话记录。</p>
      </div>
    </section>

    <div class="sidebar-footer" :class="{ compact: collapsed }">
      <router-link to="/profile" class="footer-card" :title="collapsed ? currentUser?.username : ''">
        <div class="footer-avatar">
          {{ currentUser ? currentUser.username.charAt(0).toUpperCase() : 'U' }}
        </div>
        <div v-show="!collapsed" class="footer-copy">
          <span class="footer-name">{{ currentUser?.username || '未登录' }}</span>
          <span class="footer-role">{{ currentUser?.role === 'admin' ? '管理员' : '普通用户' }}</span>
        </div>
      </router-link>
      <button
        v-show="!collapsed"
        class="logout-link"
        type="button"
        @click="handleLogout"
        title="退出登录"
      >
        退出登录
      </button>
    </div>
  </aside>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, Connection, Delete, Expand, Fold, Folder, Plus, Reading } from '@element-plus/icons-vue'
import { chatApi, authApi } from '@/api'
import { tokenStore } from '@/utils/auth'
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
const currentUser = ref(tokenStore.getUser())
const navItems = [
  {
    path: '/',
    label: '智能问答',
    description: '面向知识库的检索问答',
    icon: ChatLineSquare
  },
  {
    path: '/manage',
    label: '管理中心',
    description: '知识库、MCP 与 Skill',
    icon: Folder
  }
]

// 平台级管理入口（MCP / Skill）仅管理员可见；后端接口有 admin 校验兜底，
// 此处隐藏菜单只是体验层，真正的权限墙在服务端
const visibleNavItems = computed(() => {
  const role = currentUser.value?.role
  return navItems.filter(item => !item.adminOnly || role === 'admin')
})

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

async function refreshCurrentUser() {
  try {
    const result = await authApi.me()
    currentUser.value = result.data || null
    if (result.data) {
      tokenStore.setUser(result.data)
    }
  } catch {
    currentUser.value = null
  }
}

async function handleLogout() {
  try {
    await authApi.logout()
  } catch {
    // 忽略服务端错误
  }
  tokenStore.clear()
  currentUser.value = null
  router.push('/login')
}

onMounted(() => {
  refreshSessions()
  refreshCurrentUser()
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
  border-radius: 0;
  padding: 14px 14px 14px 4px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  overscroll-behavior: contain;
  contain: layout style paint;
  /* 桌面延伸：透明底融入 app 米色背景，仅以发丝线与工作区分隔 */
  background: transparent;
  border-right: 1px solid var(--border-light);
}

.sidebar.collapsed {
  /* width 由 GSAP 控制，CSS 不再硬切 */
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

/* 墨方印：浓墨底 + 宋体「问」+ 纸色内框，静态无动画 */
.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: var(--radius-sm, 10px);
  display: grid;
  place-items: center;
  color: var(--text-inverse, #f9f4ea);
  background: var(--text-primary, #2e2a23);
  font-family: var(--font-serif, serif);
  font-size: 20px;
  font-weight: 600;
  box-shadow: inset 0 0 0 2px rgba(249, 244, 234, 0.28), var(--shadow-xs);
  flex-shrink: 0;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.brand-title {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.brand-subtitle {
  color: var(--text-secondary);
  font-size: 11px;
}

.collapse-btn {
  width: 34px;
  height: 34px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.86);
  color: var(--text-secondary);
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--transition-fast), color var(--transition-fast), transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.collapse-btn:hover {
  background: #ffffff;
  color: var(--text-primary);
  transform: scale(var(--jelly-hover-scale, 1.03));
}

.collapse-btn:active {
  transform: scaleX(var(--jelly-press-x, 1.04)) scaleY(var(--jelly-press-y, 0.96));
}

.new-chat-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  margin-top: 14px;
  border: 1px solid rgba(166, 61, 42, 0.32);
  border-radius: var(--radius-pill);
  background: var(--primary-color);
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
  transition: transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)), background var(--transition-fast), box-shadow var(--transition-fast);
}

.new-chat-btn:hover {
  transform: scale(var(--jelly-hover-scale, 1.03));
  background: var(--primary-strong);
  box-shadow: 0 14px 24px rgba(166, 61, 42, 0.24);
}

.new-chat-btn:active {
  transform: scaleX(var(--jelly-press-x, 1.04)) scaleY(var(--jelly-press-y, 0.96));
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
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  margin-bottom: 12px;
}

.nav-block {
  display: flex;
  flex-direction: column;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 16px;
  margin-bottom: 8px;
  transition: background var(--transition-fast), transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1)), border-color var(--transition-fast);
  border: 1px solid transparent;
}

.nav-item.compact {
  justify-content: center;
  padding: 10px 0;
}

.nav-item:hover {
  background: rgba(166, 61, 42, 0.06);
  transform: scale(1.02) translateX(2px);
}

.nav-item:active {
  transform: scaleX(var(--jelly-press-x, 1.04)) scaleY(var(--jelly-press-y, 0.96));
}

.nav-item.active {
  background: linear-gradient(135deg, rgba(166, 61, 42, 0.12), rgba(166, 61, 42, 0.04));
  border-color: rgba(166, 61, 42, 0.2);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.42);
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 60%;
  border-radius: 0 3px 3px 0;
  background: var(--primary-color);
}

.nav-item-icon {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(244, 244, 245, 0.96);
  color: var(--text-secondary);
  flex-shrink: 0;
}

.nav-item.active .nav-item-icon {
  background: var(--primary-color);
  color: #ffffff;
}

.nav-item-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.nav-item-title {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
}

.nav-item-desc {
  color: var(--text-muted);
  font-size: 11px;
}

.history-block {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
}

.history-block::-webkit-scrollbar {
  width: 5px;
}

.history-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.history-caption {
  color: var(--text-muted);
  font-size: 12px;
}

.clear-btn {
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.clear-btn:hover {
  color: var(--danger-color);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 4px;
}

/* 紧凑单行制：标题一行 + 右侧时间，悬停才现删除按钮（一屏可容纳十余条） */
.history-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 34px;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast);
}

.history-item:hover {
  background: rgba(72, 60, 42, 0.06);
}

.history-item:active {
  background: rgba(72, 60, 42, 0.1);
}

.history-item.active {
  background: var(--primary-soft);
}

.history-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 22%;
  bottom: 22%;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--primary-color);
}

.history-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary-color);
  flex-shrink: 0;
}

.history-title {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item.active .history-title {
  color: var(--primary-strong);
}

.history-time {
  color: var(--text-muted);
  font-size: var(--text-xs);
  white-space: nowrap;
  flex-shrink: 0;
  transition: opacity var(--transition-fast);
}

/* 悬停时时间让位给删除按钮，避免重叠 */
.history-item:hover .history-time {
  opacity: 0;
}

.history-delete-btn {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}

.history-item:hover .history-delete-btn {
  opacity: 1;
}

.history-delete-btn:hover {
  background: var(--danger-soft);
  color: var(--danger-color);
}

.history-empty {
  flex: 1;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 12px;
  color: var(--text-secondary);
}

.history-empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: rgba(166, 61, 42, 0.08);
  color: var(--primary-strong);
}

.history-empty h3 {
  font-size: 15px;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.history-empty p {
  font-size: 12px;
  line-height: 1.6;
}

.sidebar-footer {
  margin-top: auto;
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
  border: 1px solid var(--border-light);
  padding: 12px;
  text-decoration: none;
  color: inherit;
  transition: background var(--transition-fast), border-color var(--transition-fast), transform var(--duration-jelly, 400ms) var(--spring-soft, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.footer-card:hover {
  background: rgba(255, 255, 255, 0.98);
  border-color: rgba(166, 61, 42, 0.2);
  transform: translateY(-1px);
}

.logout-link {
  display: block;
  width: 100%;
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid rgba(166, 61, 42, 0.2);
  border-radius: 12px;
  background: var(--danger-soft);
  color: var(--danger-color);
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s ease;
}

.logout-link:hover {
  background: rgba(166, 61, 42, 0.15);
  border-color: rgba(166, 61, 42, 0.3);
}

.sidebar-footer.compact .footer-card {
  justify-content: center;
  padding: 10px 0;
}

.footer-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--primary-color), var(--primary-strong));
  color: #ffffff;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}

.footer-copy {
  display: flex;
  flex-direction: column;
}

.footer-name {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
}

.footer-role {
  color: var(--text-secondary);
  font-size: 11px;
}

@media (max-width: 900px) {
  .sidebar,
  .sidebar.collapsed {
    position: relative;
    top: 0;
    left: 0;
    width: 100%;
    height: auto;
    /* 堆叠时限制侧栏高度，保证主内容区可见、可操作 */
    max-height: 52dvh;
  }

  .history-block {
    max-height: 280px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .brand-mark {
    animation: none;
  }
  .new-chat-btn,
  .nav-item,
  .collapse-btn,
  .history-item {
    transition-duration: 0.01ms !important;
  }
}
</style>
