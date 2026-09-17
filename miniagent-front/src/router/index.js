import { createRouter, createWebHistory } from 'vue-router'
import { tokenStore } from '@/utils/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      name: 'chat',
      component: () => import('@/views/ChatView.vue')
    },
    {
      path: '/manage',
      name: 'manage',
      component: () => import('@/views/ManageView.vue')
    },
    {
      path: '/kb',
      redirect: { path: '/manage', query: { tab: 'kb' } }
    },
    {
      path: '/kb/:kbId/docs',
      name: 'documents',
      component: () => import('@/views/DocumentView.vue')
    },
    {
      path: '/mcp',
      redirect: { path: '/manage', query: { tab: 'mcp' } }
    },
    {
      path: '/skills',
      redirect: { path: '/manage', query: { tab: 'skills' } }
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/UserCenterView.vue')
    }
  ]
})

router.beforeEach((to, from, next) => {
  const isLoggedIn = tokenStore.isLoggedIn()

  if (to.meta.public) {
    if (isLoggedIn && to.name === 'login') {
      next({ name: 'chat' })
    } else {
      next()
    }
    return
  }

  if (!isLoggedIn) {
    next({ name: 'login' })
    return
  }

  // 平台级管理 tab（MCP / Skill）仅管理员可见；普通用户越权访问 tab 时回落知识库。
  // 这只是体验层拦截，真正的权限墙在服务端拦截器（普通用户直连接口返回 403）
  if (to.path === '/manage' && ['mcp', 'skills'].includes(String(to.query.tab || ''))) {
    const role = tokenStore.getUser()?.role
    if (role !== 'admin') {
      next({ path: '/manage', query: { tab: 'kb' } })
      return
    }
  }

  next()
})

export default router