<template>
  <div class="auth-page">
    <div class="auth-container">
      <!-- 左侧：品牌展示区 -->
      <div class="auth-brand">
        <div class="brand-content">
          <div class="brand-logo">
            <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M16 2L28 9V23L16 30L4 23V9L16 2Z" stroke="#a63d2a" stroke-width="2" stroke-linejoin="round"/>
              <path d="M16 10L22 13.5V20.5L16 24L10 20.5V13.5L16 10Z" fill="#a63d2a" fill-opacity="0.3" stroke="#a63d2a" stroke-width="1.5"/>
              <circle cx="16" cy="17" r="2" fill="#a63d2a"/>
            </svg>
          </div>
          <h1 class="brand-title">Ragent</h1>
          <p class="brand-tagline">智能知识助手 · 多模块 RAG 引擎</p>
          <div class="brand-features">
            <div class="feature-item">
              <span class="feature-dot"></span>
              <span>多通道检索 + 意图路由</span>
            </div>
            <div class="feature-item">
              <span class="feature-dot"></span>
              <span>Agent 工具调用 + MCP 集成</span>
            </div>
            <div class="feature-item">
              <span class="feature-dot"></span>
              <span>文档异步入库 + 自动重试</span>
            </div>
            <div class="feature-item">
              <span class="feature-dot"></span>
              <span>知识库按用户隔离</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：表单区 -->
      <div class="auth-form-wrapper">
        <div class="auth-form-panel">
          <div class="form-header">
            <h2 class="form-title">{{ isLogin ? '登录' : '注册' }}</h2>
            <p class="form-subtitle">{{ isLogin ? '欢迎回来，请登录你的账号' : '创建一个新账号开始使用' }}</p>
          </div>

          <form class="auth-form" @submit.prevent="handleSubmit">
            <div class="form-field">
              <label class="field-label">用户名</label>
              <input
                v-model="form.username"
                type="text"
                class="field-input"
                placeholder="3-64 位字母数字下划线"
                autocomplete="username"
              />
            </div>

            <div class="form-field">
              <label class="field-label">密码</label>
              <input
                v-model="form.password"
                type="password"
                class="field-input"
                placeholder="6-128 位"
                :autocomplete="isLogin ? 'current-password' : 'new-password'"
              />
            </div>

            <div v-if="errorMessage" class="form-error">{{ errorMessage }}</div>

            <button type="submit" class="submit-btn" :disabled="loading">
              <span v-if="loading" class="btn-spinner"></span>
              <span>{{ isLogin ? '登录' : '注册' }}</span>
            </button>
          </form>

          <div class="form-footer">
            <span>{{ isLogin ? '还没有账号？' : '已有账号？' }}</span>
            <button type="button" class="switch-btn" @click="toggleMode">
              {{ isLogin ? '去注册' : '去登录' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api'
import { tokenStore } from '@/utils/auth'

const router = useRouter()

const isLogin = ref(true)
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: '',
  password: ''
})

function toggleMode() {
  isLogin.value = !isLogin.value
  errorMessage.value = ''
}

async function handleSubmit() {
  errorMessage.value = ''

  if (!form.username || form.username.length < 3) {
    errorMessage.value = '用户名至少 3 个字符'
    return
  }
  if (!form.password || form.password.length < 6) {
    errorMessage.value = '密码至少 6 个字符'
    return
  }

  loading.value = true
  try {
    const apiCall = isLogin.value ? authApi.login : authApi.register
    const result = await apiCall({ username: form.username, password: form.password })
    const payload = result.data || {}

    tokenStore.setToken(payload.token)
    tokenStore.setUser({ userId: payload.userId, username: payload.username, role: payload.role })

    router.push('/')
  } catch (err) {
    errorMessage.value = err.message || '操作失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at top left, rgba(166, 61, 42, 0.08), transparent 35%),
    radial-gradient(circle at bottom right, rgba(226, 232, 240, 0.5), transparent 30%),
    linear-gradient(180deg, #fafbfc 0%, #f4f6f8 100%);
  padding: 24px;
}

.auth-container {
  display: grid;
  grid-template-columns: 1fr 1fr;
  width: 100%;
  max-width: 960px;
  min-height: 560px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--border-light);
  border-radius: 24px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.06), 0 4px 16px rgba(0, 0, 0, 0.03);
  overflow: hidden;
}

/* 左侧品牌区 */
.auth-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background:
    linear-gradient(135deg, rgba(166, 61, 42, 0.06) 0%, rgba(255, 255, 255, 0.4) 100%);
  border-right: 1px solid var(--border-light);
}

.brand-content {
  max-width: 340px;
}

.brand-logo {
  width: 56px;
  height: 56px;
  margin-bottom: 24px;
}

.brand-logo svg {
  width: 100%;
  height: 100%;
}

.brand-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.02em;
  margin: 0 0 8px 0;
}

.brand-tagline {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0 0 40px 0;
  line-height: 1.6;
}

.brand-features {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: var(--text-primary);
}

.feature-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary-color);
  flex-shrink: 0;
  box-shadow: 0 0 0 3px rgba(166, 61, 42, 0.15);
}

/* 右侧表单区 */
.auth-form-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}

.auth-form-panel {
  width: 100%;
  max-width: 320px;
}

.form-header {
  margin-bottom: 32px;
}

.form-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px 0;
  letter-spacing: -0.01em;
}

.form-subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
  line-height: 1.5;
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}

.field-input {
  width: 100%;
  height: 44px;
  padding: 0 16px;
  font-size: 14px;
  font-family: inherit;
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  outline: none;
  transition: all 0.2s ease;
  box-sizing: border-box;
}

.field-input:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(166, 61, 42, 0.12);
  background: #ffffff;
}

.field-input::placeholder {
  color: var(--text-muted);
}

.form-error {
  font-size: 13px;
  color: var(--danger-color);
  background: var(--danger-soft);
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.4;
}

.submit-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 44px;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  color: #ffffff;
  background: var(--primary-color);
  border: none;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-top: 4px;
}

.submit-btn:hover:not(:disabled) {
  background: var(--primary-strong);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(166, 61, 42, 0.3);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 24px;
  font-size: 13px;
  color: var(--text-secondary);
}

.switch-btn {
  background: none;
  border: none;
  color: var(--primary-color);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
  transition: color 0.2s ease;
}

.switch-btn:hover {
  color: var(--primary-strong);
}

/* 响应式：窄屏只显示表单区 */
@media (max-width: 768px) {
  .auth-container {
    grid-template-columns: 1fr;
    max-width: 420px;
    min-height: auto;
  }

  .auth-brand {
    display: none;
  }

  .auth-form-wrapper {
    padding: 40px 24px;
  }
}
</style>