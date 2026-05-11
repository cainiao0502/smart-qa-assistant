import { ElNotification } from 'element-plus'

const ERROR_THROTTLE_MS = 3000
const recentErrors = new Map()

function isThrottled(key) {
  const now = Date.now()
  const lastShown = recentErrors.get(key)
  if (lastShown && now - lastShown < ERROR_THROTTLE_MS) {
    return true
  }
  recentErrors.set(key, now)
  if (recentErrors.size > 50) {
    const oldest = [...recentErrors.entries()].sort((a, b) => a[1] - b[1])
    for (let i = 0; i < 25; i++) {
      recentErrors.delete(oldest[i][0])
    }
  }
  return false
}

function formatMessage(error) {
  if (!error) return '未知错误'
  if (typeof error === 'string') return error
  const msg = error.message || error.msg || ''
  if (msg.includes('Failed to fetch') || msg.includes('Network Error')) {
    return '网络连接失败，请检查后端服务是否启动'
  }
  if (msg.includes('timeout') || msg.includes('ECONNABORTED')) {
    return '请求超时，请稍后重试'
  }
  if (error.response) {
    const status = error.response.status
    if (status === 401) return '认证失败，请重新登录'
    if (status === 403) return '无权限执行此操作'
    if (status === 404) return '请求的资源不存在'
    if (status >= 500) return '服务器内部错误，请稍后重试'
  }
  return msg || '操作失败'
}

export function showGlobalError(error, title = '操作失败') {
  const message = formatMessage(error)
  if (isThrottled(message)) return

  ElNotification({
    title,
    message,
    type: 'error',
    duration: 5000,
    position: 'top-right'
  })
}

export function installGlobalErrorHandler(app) {
  app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue Error]', info, err)
    showGlobalError(err, '页面错误')
  }

  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    if (reason && reason.name === 'NavigationDuplicated') return
    if (reason && reason.message && reason.message.includes('AbortError')) return
    console.error('[Unhandled Rejection]', reason)
    showGlobalError(reason, '未处理的异常')
  })

  window.addEventListener('error', (event) => {
    if (event.message === 'Script error.') return
    console.error('[Window Error]', event.message)
    showGlobalError(event.message, '脚本错误')
  })
}
