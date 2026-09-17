<template>
  <div class="user-center-page">
    <div class="page-header">
      <h1 class="page-title">个人中心</h1>
      <p class="page-subtitle">管理你的账号信息</p>
    </div>

    <div class="profile-card" v-if="userInfo">
      <div class="avatar-section">
        <div class="avatar">
          {{ userInfo.username.charAt(0).toUpperCase() }}
        </div>
        <div class="user-meta">
          <h2 class="user-name">{{ userInfo.username }}</h2>
          <span class="user-role-tag" :class="{ 'is-admin': userInfo.role === 'admin' }">
            {{ userInfo.role === 'admin' ? '管理员' : '普通用户' }}
          </span>
        </div>
      </div>

      <div class="info-section">
        <div class="info-row">
          <span class="info-label">用户 ID</span>
          <span class="info-value">{{ userInfo.userId }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">用户名</span>
          <span class="info-value">{{ userInfo.username }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">角色</span>
          <span class="info-value">{{ userInfo.role }}</span>
        </div>
      </div>

      <div class="action-section">
        <button class="logout-btn" @click="handleLogout">
          退出登录
        </button>
      </div>
    </div>

    <div v-else class="loading-state">
      <span class="loading-spinner"></span>
      <span>加载中...</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api'
import { tokenStore } from '@/utils/auth'

const router = useRouter()
const userInfo = ref(null)

onMounted(async () => {
  try {
    const result = await authApi.me()
    userInfo.value = result.data || null
  } catch (err) {
    tokenStore.clear()
    router.push('/login')
  }
})

async function handleLogout() {
  try {
    await authApi.logout()
  } catch {
    // 忽略服务端错误，前端直接清理
  }
  tokenStore.clear()
  router.push('/login')
}
</script>

<style scoped>
.user-center-page {
  padding: 40px 48px;
  max-width: 720px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px 0;
  letter-spacing: -0.02em;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.profile-card {
  background: var(--bg-surface-strong);
  border: 1px solid var(--border-light);
  border-radius: 20px;
  padding: 32px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.04);
}

.avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
  padding-bottom: 28px;
  border-bottom: 1px solid var(--border-light);
  margin-bottom: 28px;
}

.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary-color), var(--primary-strong));
  color: #ffffff;
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(166, 61, 42, 0.25);
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.user-name {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.user-role-tag {
  display: inline-block;
  width: fit-content;
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary-strong);
}

.user-role-tag.is-admin {
  background: rgba(162, 115, 44, 0.12);
  color: #8a6224;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 32px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
}

.info-label {
  font-size: 14px;
  color: var(--text-secondary);
  font-weight: 500;
}

.info-value {
  font-size: 14px;
  color: var(--text-primary);
  font-family: monospace;
}

.action-section {
  padding-top: 24px;
  border-top: 1px solid var(--border-light);
}

.logout-btn {
  width: 100%;
  height: 44px;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  color: var(--danger-color);
  background: var(--danger-soft);
  border: 1px solid rgba(166, 61, 42, 0.2);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.logout-btn:hover {
  background: rgba(166, 61, 42, 0.15);
  border-color: rgba(166, 61, 42, 0.3);
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 0;
  color: var(--text-secondary);
  font-size: 14px;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--border-light);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>