import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'chat',
      component: () => import('@/views/ChatView.vue')
    },
    {
      path: '/kb',
      name: 'knowledge-base',
      component: () => import('@/views/KnowledgeBaseView.vue')
    },
    {
      path: '/kb/:kbId/docs',
      name: 'documents',
      component: () => import('@/views/DocumentView.vue')
    }
  ]
})

export default router
