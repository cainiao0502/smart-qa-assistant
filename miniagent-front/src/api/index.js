import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

// Response interceptor
api.interceptors.response.use(
  response => {
    const payload = response.data

    if (payload && typeof payload === 'object' && 'code' in payload && payload.code !== 'SUCCESS') {
      return Promise.reject(new Error(payload.message || 'Request failed'))
    }

    return payload
  },
  error => {
    const message = error.response?.data?.message || error.message || 'Request failed'
    return Promise.reject(new Error(message))
  }
)

// Knowledge Base APIs
export const kbApi = {
  list: () => api.get('/kb'),
  create: (data) => api.post('/kb', data),
  delete: (kbId) => api.delete(`/kb/${kbId}`),
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
  index: (docId, forceReindex = false) => api.post(`/documents/${docId}/index?forceReindex=${forceReindex}`),
  delete: (docId) => api.delete(`/documents/${docId}`),
}

// Chat APIs
export const chatApi = {
  chat: (data) => api.post('/chat', data),
  getMessages: (sessionId) => api.get(`/chat/sessions/${sessionId}/messages`),
}

export default api
