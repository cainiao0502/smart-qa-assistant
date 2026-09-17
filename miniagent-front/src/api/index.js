import axios from 'axios'
import { tokenStore } from '@/utils/auth'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

// Request interceptor: inject Bearer token
api.interceptors.request.use(config => {
  const token = tokenStore.getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const UNAUTHORIZED_CODES = Object.freeze(['UNAUTHORIZED'])

function redirectToLogin() {
  tokenStore.clear()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

// Response interceptor
api.interceptors.response.use(
  response => {
    const payload = response.data

    if (payload && typeof payload === 'object' && 'code' in payload) {
      if (UNAUTHORIZED_CODES.includes(payload.code)) {
        redirectToLogin()
        return Promise.reject(new Error('未登录，请重新登录'))
      }
      if (payload.code !== 'SUCCESS') {
        return Promise.reject(new Error(payload.message || 'Request failed'))
      }
    }

    return payload
  },
  error => {
    const status = error.response?.status
    const payloadCode = error.response?.data?.code
    if (status === 401 || UNAUTHORIZED_CODES.includes(payloadCode)) {
      redirectToLogin()
    }
    const message = error.response?.data?.message || error.message || 'Request failed'
    return Promise.reject(new Error(message))
  }
)

// Auth APIs
export const authApi = {
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  logout: () => api.post('/auth/logout'),
  me: () => api.get('/auth/me'),
}

// Knowledge Base APIs
export const kbApi = {
  list: () => api.get('/kb'),
  create: (data) => api.post('/kb', data),
  delete: (kbId) => api.delete(`/kb/${kbId}`),
}

export const skillApi = {
  list: () => api.get('/skills'),
  getDetail: (name) => api.get(`/skills/${name}`),
  create: (data) => api.post('/skills', data),
  update: (name, data) => api.put(`/skills/${name}`, data),
  delete: (name) => api.delete(`/skills/${name}`),
  execute: (name, data) => api.post(`/skills/${name}/execute`, data),
  importFile: ({ file, name = '', overwrite = false }) => {
    const formData = new FormData()
    formData.append('file', file)
    if (name) {
      formData.append('name', name)
    }
    formData.append('overwrite', String(overwrite))
    return api.post('/skills/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  reload: () => api.post('/skills/reload'),
}

// Document APIs
export const docApi = {
  list: (kbId) => api.get(`/kb/${kbId}/documents`),
  upload: (kbId, file) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post(`/kb/${kbId}/documents/upload`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  // 查询文档详情，包含原文内容和切片列表
  getDetail: (docId) => api.get(`/documents/${docId}`),
  // 触发文档异步入库，立即返回 { id: taskId, status, progress, ... }
  index: (docId, forceReindex = false) => api.post(`/documents/${docId}/index?forceReindex=${forceReindex}`),
  delete: (docId) => api.delete(`/documents/${docId}`),
}

// 异步入库任务查询 API，前端轮询获取任务状态和进度
export const taskApi = {
  // 按任务 ID 查询状态，返回 { status, progress, errorMessage, ... }
  get: (taskId) => api.get(`/tasks/${taskId}`),
}

// Chat APIs
export const chatApi = {
  chat: (data) => api.post('/chat', data),
  stream: (data, options = {}) => {
    const token = tokenStore.getToken()
    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
    return fetch('/api/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
      signal: options.signal
    }).then(res => {
      if (res.status === 401) {
        tokenStore.clear()
        if (window.location.pathname !== '/login') {
          window.location.href = '/login'
        }
        throw new Error('未登录，请重新登录')
      }
      return res
    })
  },
  getMessages: (sessionId) => api.get(`/chat/sessions/${sessionId}/messages`),
  getRunDetail: (runId) => api.get(`/chat/runs/${runId}`),
  listSessions: () => api.get('/chat/sessions'),
  deleteSession: (sessionId) => api.delete(`/chat/sessions/${sessionId}`),
  // 提交工具审批决定。SSE 是单向的，所以结论必须走独立请求送回服务端。
  decideApproval: (approvalId, approved) => api.post(`/chat/approvals/${approvalId}`, { approved }),
}

export const mcpApi = {
  overview: () => api.get('/mcp/servers'),
  create: (data) => api.post('/mcp/servers', data),
  update: (serverId, data) => api.put(`/mcp/servers/${serverId}`, data),
  delete: (serverId) => api.delete(`/mcp/servers/${serverId}`),
  start: (serverId) => api.post(`/mcp/servers/${serverId}/start`),
  stop: (serverId) => api.post(`/mcp/servers/${serverId}/stop`),
}

export default api
