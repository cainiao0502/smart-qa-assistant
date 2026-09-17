<template>
  <section class="manage-page">
    <nav class="manage-tabs" role="tablist" aria-label="管理中心导航">
      <button
        v-for="tab in visibleTabs"
        :key="tab.key"
        type="button"
        role="tab"
        class="manage-tab"
        :class="{ active: activeTab === tab.key }"
        :aria-selected="activeTab === tab.key"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>
    <div class="manage-body">
      <component :is="activeComponent" />
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tokenStore } from '@/utils/auth'
import KnowledgeBaseView from '@/views/KnowledgeBaseView.vue'
import McpView from '@/views/McpView.vue'
import SkillView from '@/views/SkillView.vue'

const route = useRoute()
const router = useRouter()

const isAdmin = computed(() => tokenStore.getUser()?.role === 'admin')

const tabs = [
  { key: 'kb', label: '知识库', component: KnowledgeBaseView, adminOnly: false },
  { key: 'mcp', label: 'MCP 服务', component: McpView, adminOnly: true },
  { key: 'skills', label: 'Skill', component: SkillView, adminOnly: true }
]

const visibleTabs = computed(() => tabs.filter((t) => !t.adminOnly || isAdmin.value))

/* 当前 tab：来自 query.tab，非法值或越权 tab 一律回落知识库 */
const activeTab = computed(() => {
  const requested = String(route.query.tab || 'kb')
  const hit = tabs.find((t) => t.key === requested && (!t.adminOnly || isAdmin.value))
  return hit ? hit.key : 'kb'
})

const activeComponent = computed(() => tabs.find((t) => t.key === activeTab.value).component)

function switchTab(key) {
  router.replace({ path: '/manage', query: { tab: key } })
}
</script>

<style scoped>
.manage-page {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 下划线式 tab：发丝底线 + 朱砂激活指示，安静不抢戏 */
.manage-tabs {
  display: flex;
  gap: 4px;
  padding: 2px 8px 0;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}

.manage-tab {
  position: relative;
  border: 0;
  background: transparent;
  cursor: pointer;
  padding: 10px 16px 12px;
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  font-family: inherit;
  color: var(--text-secondary);
  transition: color var(--transition-fast);
}

.manage-tab:hover {
  color: var(--text-primary);
}

.manage-tab.active {
  color: var(--primary-strong);
  font-weight: var(--weight-semibold);
}

.manage-tab.active::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--primary-color);
}

.manage-body {
  flex: 1;
  min-height: 0;
}
</style>
