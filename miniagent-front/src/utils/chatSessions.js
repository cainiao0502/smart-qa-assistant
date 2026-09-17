import { tokenStore } from '@/utils/auth'

const STORAGE_KEY = 'miniagent-chat-sessions'
const UPDATE_EVENT = 'miniagent-chat-sessions-updated'
const MAX_SESSIONS = 12

// 存储键按用户隔离：换账号 / 换数据库后，旧账号的会话列表不会串到新账号的侧栏
function storageKey() {
  const user = tokenStore.getUser()
  return user && user.id ? `${STORAGE_KEY}:${user.id}` : `${STORAGE_KEY}:guest`
}

function parseStoredSessions() {
  try {
    const raw = window.localStorage.getItem(storageKey())
    const sessions = raw ? JSON.parse(raw) : []
    return Array.isArray(sessions) ? sessions : []
  } catch {
    return []
  }
}

function persistSessions(sessions) {
  window.localStorage.setItem(storageKey(), JSON.stringify(sessions))
  window.dispatchEvent(new CustomEvent(UPDATE_EVENT))
}

export function listRecentSessions() {
  return parseStoredSessions().sort((a, b) => {
    return new Date(b.updatedAt || 0).getTime() - new Date(a.updatedAt || 0).getTime()
  })
}

export function upsertRecentSession(session) {
  const sessions = parseStoredSessions().filter((item) => item.id !== session.id)
  sessions.unshift({
    id: session.id,
    title: session.title || '未命名会话',
    preview: session.preview || '',
    kbId: session.kbId ?? null,
    kbName: session.kbName || '',
    updatedAt: session.updatedAt || new Date().toISOString()
  })
  persistSessions(sessions.slice(0, MAX_SESSIONS))
}

export function removeRecentSession(sessionId) {
  persistSessions(parseStoredSessions().filter((item) => item.id !== sessionId))
}

export function clearRecentSessions() {
  persistSessions([])
}

export function subscribeRecentSessions(callback) {
  const handler = () => callback(listRecentSessions())
  window.addEventListener(UPDATE_EVENT, handler)
  window.addEventListener('storage', handler)

  return () => {
    window.removeEventListener(UPDATE_EVENT, handler)
    window.removeEventListener('storage', handler)
  }
}
