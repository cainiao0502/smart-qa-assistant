<template>
  <section class="mcp-page">
    <div class="mcp-shell glass-panel" v-loading="loading">
      <header class="mcp-hero">
        <div class="mcp-hero-copy">
          <span class="soft-chip">MCP Management</span>
          <h1>在一个页面里统一管理 MCP 服务、启动命令和工具暴露情况。</h1>
          <p>这里会同时展示后端内置的 MCP 配置，以及你从前端新增的自定义 MCP。</p>
        </div>

        <div class="mcp-hero-actions">
          <el-button type="primary" @click="openCreateDialog">
            <el-icon><Plus /></el-icon>
            添加 MCP
          </el-button>
          <el-button plain @click="fetchOverview">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </header>

      <section class="mcp-stats">
        <article v-for="card in statsCards" :key="card.label" class="stat-card">
          <div class="stat-icon" :style="{ background: card.bgColor, color: card.color }">
            <el-icon><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-copy">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.description }}</small>
          </div>
        </article>
      </section>

      <section class="mcp-board glass-panel">
        <div class="mcp-board-header">
          <div>
            <span class="section-heading">服务概览</span>
            <h2>{{ servers.length }} 个 MCP 服务</h2>
          </div>
          <span class="board-tip">
            请求超时 {{ overview.requestTimeoutMs || 0 }}ms · 工具缓存 {{ overview.toolCacheSeconds || 0 }}s
          </span>
        </div>

        <div v-if="!overview.enabled" class="global-banner warning">
          <el-icon><WarningFilled /></el-icon>
          <span>当前 `APP_MCP_ENABLED` 处于关闭状态。这里仍然会显示配置，但 MCP 工具不会参与实际调用。</span>
        </div>

        <div v-if="servers.length" class="server-list">
          <article v-for="server in servers" :key="server.serverId" class="server-card">
            <div class="server-card-top">
              <div class="server-main">
                <div class="server-title-row">
                  <div class="server-title-block">
                    <h3>{{ server.serverId }}</h3>
                    <div class="server-title-tags">
                      <span class="source-pill" :class="server.custom ? 'custom' : 'builtin'">
                        {{ server.custom ? '自定义' : '内置' }}
                      </span>
                      <span class="status-pill" :class="primaryStatusClass(server)">
                        <i></i>
                        {{ primaryStatusText(server) }}
                      </span>
                    </div>
                  </div>
                </div>

                <p class="server-url">
                  {{
                    server.transportType === 'stdio'
                      ? formatLaunchCommand([server.command, ...(server.args || [])].filter(Boolean))
                      : (server.baseUrl || 'No baseUrl configured')
                  }}
                </p>
              </div>

              <div class="server-side">
                <div class="server-metrics">
                  <div class="metric-chip">
                    <span>类型</span>
                    <strong>{{ transportLabel(server.transportType) }}</strong>
                  </div>
                  <div class="metric-chip">
                    <span>工具数</span>
                    <strong>{{ server.toolCount }}</strong>
                  </div>
                  <div class="metric-chip">
                    <span>前缀</span>
                    <strong>{{ server.toolNamePrefix || '-' }}</strong>
                  </div>
                </div>

                <div class="server-actions">
                  <el-button
                    v-if="server.custom"
                    plain
                    size="small"
                    @click="openEditDialog(server)"
                  >
                    <el-icon><Edit /></el-icon>
                    编辑
                  </el-button>
                  <el-button
                    v-if="server.custom"
                    plain
                    size="small"
                    :loading="actionServerId === server.serverId && actionType === 'delete'"
                    @click="handleDelete(server.serverId)"
                  >
                    <el-icon><Delete /></el-icon>
                    删除
                  </el-button>

                  <el-button
                    v-if="!server.processStatus?.running"
                    type="primary"
                    size="small"
                    :disabled="!server.processStatus?.launchConfigured"
                    :loading="actionServerId === server.serverId && actionType === 'start'"
                    @click="handleStart(server.serverId)"
                  >
                    <el-icon><VideoPlay /></el-icon>
                    启动
                  </el-button>

                  <el-button
                    v-if="server.processStatus?.running"
                    plain
                    size="small"
                    :loading="actionServerId === server.serverId && actionType === 'stop'"
                    @click="handleStop(server.serverId)"
                  >
                    <el-icon><VideoPause /></el-icon>
                    停止
                  </el-button>
                  <el-button plain size="small" @click="toggleServerExpanded(server.serverId)">
                    <el-icon><component :is="isServerExpanded(server.serverId) ? ArrowUp : ArrowDown" /></el-icon>
                    {{ isServerExpanded(server.serverId) ? '收起' : '展开' }}
                  </el-button>
                </div>
              </div>
            </div>

            <div v-if="isServerExpanded(server.serverId)" class="server-details">
              <div class="server-meta-grid">
                <div class="meta-item">
                  <span>进程状态</span>
                  <strong>{{ server.processStatus?.running ? '运行中' : '未运行' }}</strong>
                </div>
                <div class="meta-item">
                  <span>启用状态</span>
                  <strong>{{ configSwitchText(server) }}</strong>
                </div>
                <div class="meta-item">
                  <span>配置完整性</span>
                  <strong>{{ server.configured ? '已就绪' : '不完整' }}</strong>
                </div>
                <div class="meta-item">
                  <span>API Key</span>
                  <strong>{{ server.hasApiKey ? '已配置' : '未填写' }}</strong>
                </div>
              </div>

              <div class="transport-panel">
                <div class="tool-section-header">
                  <span class="subheading">传输配置</span>
                  <small>{{ transportLabel(server.transportType) }}</small>
                </div>

                <div class="server-meta-grid">
                  <div class="meta-item">
                    <span>服务地址</span>
                    <strong class="wrap-text">{{ server.baseUrl || '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>命令</span>
                    <strong class="wrap-text">{{ server.command || '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>Args</span>
                    <strong class="wrap-text">{{ server.args?.length ? server.args.join(' ') : '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>请求头数量</span>
                    <strong>{{ server.headerCount }}</strong>
                  </div>
                </div>
              </div>

              <div v-if="server.allowedTools && server.allowedTools.length" class="allowed-tools">
                <span class="subheading">允许的工具</span>
                <div class="tag-row">
                  <span v-for="toolName in server.allowedTools" :key="toolName" class="tag-chip">
                    {{ toolName }}
                  </span>
                </div>
              </div>

              <div class="process-panel">
                <div class="tool-section-header">
                  <span class="subheading">本地进程</span>
                  <small>
                    {{
                      server.processStatus?.launchConfigured
                        ? (server.processStatus?.running ? '运行中' : '已配置启动命令')
                        : '缺少启动命令'
                    }}
                  </small>
                </div>

                <div class="server-meta-grid">
                  <div class="meta-item">
                    <span>PID</span>
                    <strong>{{ server.processStatus?.pid || '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>工作目录</span>
                    <strong class="wrap-text">{{ server.processStatus?.workingDirectory || '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>退出码</span>
                    <strong>{{ server.processStatus?.exitCode ?? '-' }}</strong>
                  </div>
                  <div class="meta-item">
                    <span>可启动</span>
                    <strong>{{ server.processStatus?.launchConfigured ? '是' : '否' }}</strong>
                  </div>
                </div>

                <div v-if="!server.processStatus?.launchConfigured" class="server-warning neutral">
                  <el-icon><InfoFilled /></el-icon>
                  <span>当前服务还没有可用的启动命令，所以前端只能展示配置，无法直接启动。</span>
                </div>

                <div v-if="server.processStatus?.launchConfigured" class="launch-command-block">
                  <span class="subheading">启动命令</span>
                  <pre>{{ formatLaunchCommand(server.processStatus?.launchCommand) }}</pre>
                </div>

                <div
                  v-if="server.processStatus?.recentLogs && server.processStatus.recentLogs.length"
                  class="launch-command-block"
                >
                  <span class="subheading">最近日志</span>
                  <pre>{{ server.processStatus.recentLogs.join('\n') }}</pre>
                </div>
              </div>

              <div v-if="server.errorMessage" class="server-warning">
                <el-icon><InfoFilled /></el-icon>
                <span>{{ server.errorMessage }}</span>
              </div>

              <div class="tool-section">
                <div class="tool-section-header">
                  <span class="subheading">暴露工具</span>
                  <small>{{ server.toolCount }} 个工具</small>
                </div>

                <div v-if="server.tools && server.tools.length" class="tool-grid">
                  <div v-for="tool in server.tools" :key="`${server.serverId}-${tool.exposedName}`" class="tool-card">
                    <div class="tool-title">{{ tool.exposedName }}</div>
                    <div class="tool-remote">{{ tool.remoteName }}</div>
                    <p>{{ tool.description || '暂未提供工具描述。' }}</p>
                  </div>
                </div>

                <div v-else class="tool-empty">
                  <span>当前没有读取到这个 MCP 服务的可用工具。</span>
                </div>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="mcp-empty">
          <div class="mcp-empty-icon">
            <el-icon><Connection /></el-icon>
          </div>
          <h3>还没有 MCP 服务</h3>
          <p>你可以直接从这个页面新增，也可以继续使用后端 `application.yml` / `.env` 里的配置。</p>
          <el-button type="primary" @click="openCreateDialog">
            <el-icon><Plus /></el-icon>
            添加 MCP
          </el-button>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="createDialogVisible"
      :title="editingServerId ? '编辑自定义 MCP' : '添加自定义 MCP'"
      width="680px"
      destroy-on-close
    >
      <div class="create-form">
        <div class="form-item">
          <span class="form-label">服务 ID</span>
          <el-input
            v-model="createForm.serverId"
            placeholder="例如：websearch-custom"
            clearable
            :disabled="Boolean(editingServerId)"
          />
        </div>

        <div class="form-item">
          <span class="form-label">配置 JSON</span>
          <el-input
            v-model="createForm.configText"
            type="textarea"
            :rows="16"
            resize="vertical"
            spellcheck="false"
          />
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="createSubmitting" @click="handleCreate">
            {{ editingServerId ? '保存修改' : '保存 MCP' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mcpApi } from '@/api'
import {
  ArrowDown,
  ArrowUp,
  Connection,
  Delete,
  Edit,
  InfoFilled,
  Lightning,
  Plus,
  Promotion,
  Refresh,
  SetUp,
  VideoPause,
  VideoPlay,
  WarningFilled
} from '@element-plus/icons-vue'

const loading = ref(false)
const actionServerId = ref('')
const actionType = ref('')
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const editingServerId = ref('')
const expandedServerIds = ref([])
const DEFAULT_CONFIG_TEXT = `{
  "type": "streamable-http",
  "baseUrl": "http://localhost:3000/mcp",
  "apiKey": "",
  "toolNamePrefix": ""
}

// Supported types: "streamable-http" | "sse" | "stdio"
// SSE example:
// { "type": "sse", "baseUrl": "http://localhost:3000/sse" }
// Stdio example:
// { "type": "stdio", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-web-search"], "toolNamePrefix": "web" }`

const createForm = ref({
  serverId: '',
  configText: DEFAULT_CONFIG_TEXT
})

const overview = ref({
  enabled: false,
  requestTimeoutMs: 0,
  toolCacheSeconds: 0,
  serverCount: 0,
  enabledServerCount: 0,
  availableServerCount: 0,
  totalToolCount: 0,
  servers: []
})

const servers = computed(() => overview.value.servers || [])

const statsCards = computed(() => [
  {
    label: 'MCP 总数',
    value: overview.value.serverCount || 0,
    description: '当前后端识别到的 MCP 服务数',
    icon: Connection,
    color: '#4456f6',
    bgColor: 'rgba(91, 108, 255, 0.12)'
  },
  {
    label: '已启用',
    value: overview.value.enabledServerCount || 0,
    description: '当前处于启用状态的服务配置',
    icon: SetUp,
    color: '#119b7f',
    bgColor: 'rgba(30, 200, 165, 0.12)'
  },
  {
    label: '可连接',
    value: overview.value.availableServerCount || 0,
    description: '成功返回工具元数据的服务',
    icon: Lightning,
    color: '#d68a10',
    bgColor: 'rgba(255, 182, 72, 0.16)'
  },
  {
    label: '暴露工具数',
    value: overview.value.totalToolCount || 0,
    description: '当前智能体可见的 MCP 工具数',
    icon: Promotion,
    color: '#4d8dff',
    bgColor: 'rgba(77, 141, 255, 0.14)'
  }
])

function primaryStatusText(server) {
  if (server.processStatus?.running) return '运行中'
  return {
    AVAILABLE: '可用',
    UNAVAILABLE: '不可用',
    STOPPED: '已停止',
    DISABLED: '已禁用',
    DISABLED_GLOBAL: '全局关闭',
    NOT_CONFIGURED: '未配置'
  }[server.status] || server.status
}

function primaryStatusClass(server) {
  if (server.processStatus?.running) return 'success'
  return {
    AVAILABLE: 'success',
    UNAVAILABLE: 'warning',
    STOPPED: 'info',
    DISABLED: 'muted',
    DISABLED_GLOBAL: 'danger',
    NOT_CONFIGURED: 'info'
  }[server.status] || 'info'
}

function configSwitchText(server) {
  if (!overview.value.enabled) return '全局关闭'
  return server.enabled ? '已启用' : '已禁用'
}

function transportLabel(type) {
  const labels = { 'streamable-http': 'Streamable HTTP', 'sse': 'SSE', 'stdio': 'Stdio' }
  return labels[type] || type || 'unknown'
}

function formatLaunchCommand(command) {
  if (!command || !command.length) {
    return '-'
  }
  return command.join(' ')
}

function openCreateDialog() {
  editingServerId.value = ''
  createForm.value = {
    serverId: '',
    configText: DEFAULT_CONFIG_TEXT
  }
  createDialogVisible.value = true
}

function openEditDialog(server) {
  editingServerId.value = server.serverId
  createForm.value = {
    serverId: server.serverId,
    configText: JSON.stringify({
      enabled: server.enabled,
      type: server.transportType,
      baseUrl: server.baseUrl || undefined,
      apiKey: server.apiKey || undefined,
      command: server.command || undefined,
      args: server.args?.length ? server.args : undefined,
      toolNamePrefix: server.toolNamePrefix || undefined,
      allowedTools: server.allowedTools?.length ? server.allowedTools : undefined,
      workingDirectory: server.workingDirectory || server.processStatus?.workingDirectory || undefined,
      headers: server.headers && Object.keys(server.headers).length ? server.headers : undefined,
      env: server.environment && Object.keys(server.environment).length ? server.environment : undefined
    }, null, 2)
  }
  createDialogVisible.value = true
}

function isServerExpanded(serverId) {
  return expandedServerIds.value.includes(serverId)
}

function toggleServerExpanded(serverId) {
  if (isServerExpanded(serverId)) {
    expandedServerIds.value = expandedServerIds.value.filter((id) => id !== serverId)
    return
  }
  expandedServerIds.value = [...expandedServerIds.value, serverId]
}

async function handleCreate() {
  const serverId = createForm.value.serverId?.trim()
  if (!serverId) {
    ElMessage.warning('请先填写服务 ID')
    return
  }

  let config
  try {
    config = JSON.parse(createForm.value.configText || '{}')
  } catch {
    ElMessage.error('配置 JSON 格式不正确')
    return
  }

  createSubmitting.value = true
  try {
    const payload = {
      serverId,
      enabled: config.enabled ?? true,
      type: config.type,
      baseUrl: config.baseUrl,
      apiKey: config.apiKey,
      toolNamePrefix: config.toolNamePrefix,
      allowedTools: config.allowedTools,
      command: config.command,
      args: config.args,
      workingDirectory: config.workingDirectory,
      headers: config.headers,
      environment: config.env ?? config.environment
    }
    if (editingServerId.value) {
      await mcpApi.update(editingServerId.value, payload)
      ElMessage.success('已更新自定义 MCP')
    } else {
      await mcpApi.create(payload)
      ElMessage.success('已添加自定义 MCP')
    }
    createDialogVisible.value = false
    editingServerId.value = ''
    await fetchOverview()
  } catch (error) {
    ElMessage.error(error.message || (editingServerId.value ? '更新 MCP 失败' : '添加 MCP 失败'))
  } finally {
    createSubmitting.value = false
  }
}

async function fetchOverview() {
  loading.value = true
  try {
    const response = await mcpApi.overview()
    overview.value = response.data || overview.value
  } catch (error) {
    ElMessage.error(error.message || '获取 MCP 概览失败')
  } finally {
    loading.value = false
  }
}

async function handleDelete(serverId) {
  try {
    await ElMessageBox.confirm(
      `确认删除自定义 MCP 服务“${serverId}”吗？`,
      '删除 MCP',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  actionServerId.value = serverId
  actionType.value = 'delete'
  try {
    await mcpApi.delete(serverId)
    ElMessage.success('已删除自定义 MCP')
    await fetchOverview()
  } catch (error) {
    ElMessage.error(error.message || '删除 MCP 失败')
  } finally {
    actionServerId.value = ''
    actionType.value = ''
  }
}

async function handleStart(serverId) {
  actionServerId.value = serverId
  actionType.value = 'start'
  try {
    await mcpApi.start(serverId)
    ElMessage.success('MCP 服务已启动')
    await fetchOverview()
  } catch (error) {
    ElMessage.error(error.message || '启动 MCP 服务失败')
  } finally {
    actionServerId.value = ''
    actionType.value = ''
  }
}

async function handleStop(serverId) {
  actionServerId.value = serverId
  actionType.value = 'stop'
  try {
    await mcpApi.stop(serverId)
    ElMessage.success('MCP 服务已停止')
    await fetchOverview()
  } catch (error) {
    ElMessage.error(error.message || '停止 MCP 服务失败')
  } finally {
    actionServerId.value = ''
    actionType.value = ''
  }
}

onMounted(() => {
  fetchOverview()
})
</script>

<style scoped>
.mcp-page {
  height: 100%;
  min-height: 0;
}

.mcp-shell {
  height: 100%;
  min-height: 0;
  overflow: auto;
  border-radius: var(--radius-xl);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(246, 250, 255, 0.9));
}

.mcp-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px;
  border-radius: 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(243, 248, 255, 0.96)),
    radial-gradient(circle at top left, rgba(123, 211, 255, 0.16), transparent 34%);
  border: 1px solid rgba(194, 211, 229, 0.44);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    0 16px 34px rgba(163, 183, 209, 0.14);
}

.mcp-hero-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mcp-hero h1 {
  max-width: 820px;
  font-size: 24px;
  line-height: 1.24;
  margin: 10px 0 8px;
  color: var(--text-primary);
}

.mcp-hero p {
  max-width: 760px;
  color: var(--text-secondary);
  line-height: 1.6;
  font-size: 13px;
}

.mcp-hero-actions {
  display: flex;
  gap: 10px;
}

.mcp-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(247, 250, 254, 0.96));
  border: 1px solid rgba(198, 214, 231, 0.52);
  box-shadow: var(--shadow-xs);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 18px;
  flex-shrink: 0;
}

.stat-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-copy span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.stat-copy strong {
  font-size: 20px;
  color: var(--text-primary);
}

.stat-copy small {
  color: var(--text-muted);
  line-height: 1.5;
}

.mcp-board {
  flex: none;
  min-height: auto;
  overflow: visible;
  border-radius: 24px;
  padding: 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(244, 249, 254, 0.96));
  border: 1px solid rgba(194, 211, 229, 0.4);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 14px 30px rgba(165, 185, 210, 0.12);
}

.mcp-board-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.mcp-board-header h2 {
  margin-top: 8px;
  font-size: 18px;
  color: var(--text-primary);
}

.board-tip {
  color: var(--text-muted);
  font-size: 12px;
}

.global-banner,
.server-warning {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 16px;
  font-size: 13px;
  font-weight: 600;
}

.global-banner {
  margin-bottom: 14px;
}

.global-banner.warning,
.server-warning {
  background: rgba(255, 182, 72, 0.14);
  border: 1px solid rgba(255, 182, 72, 0.26);
  color: #a66b0d;
}

.server-warning.neutral {
  background: rgba(77, 141, 255, 0.1);
  border-color: rgba(77, 141, 255, 0.2);
  color: #4a6f98;
}

.server-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 14px;
}

.server-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border-radius: 22px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 250, 255, 0.96));
  border: 1px solid rgba(198, 214, 231, 0.58);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.84),
    0 12px 24px rgba(168, 188, 212, 0.1);
}

.server-card-top,
.tool-section-header,
.server-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.server-main {
  min-width: 0;
  flex: 1;
}

.server-title-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.server-title-row h3 {
  margin: 0;
  font-size: 18px;
  color: var(--text-primary);
}

.server-title-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.server-url {
  margin-top: 8px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.server-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.server-metrics {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 108px;
}

.server-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.metric-chip {
  padding: 10px 12px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(236, 243, 255, 0.96), rgba(245, 249, 255, 0.98));
  border: 1px solid rgba(201, 217, 235, 0.52);
}

.metric-chip span {
  display: block;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.metric-chip strong {
  display: block;
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 14px;
}

.server-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.meta-item {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(247, 250, 254, 0.96);
  border: 1px solid rgba(220, 231, 243, 0.92);
}

.meta-item span,
.subheading {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.meta-item strong {
  display: block;
  margin-top: 6px;
  color: var(--text-primary);
  font-size: 13px;
}

.allowed-tools,
.tool-section,
.process-panel,
.transport-panel,
.server-details {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.server-details {
  padding-top: 2px;
}

.launch-command-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.launch-command-block pre {
  margin: 0;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(248, 251, 255, 0.96);
  border: 1px solid rgba(201, 217, 235, 0.52);
  color: #4d657f;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.wrap-text {
  word-break: break-word;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-chip {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(228, 240, 255, 0.96);
  color: #5f7ea2;
  font-size: 11px;
  font-weight: 700;
}

.tool-section-header small {
  color: var(--text-muted);
  font-size: 12px;
}

.tool-grid {
  display: grid;
  gap: 10px;
}

.tool-card {
  padding: 14px;
  border-radius: 16px;
  background: rgba(248, 251, 255, 0.96);
  border: 1px solid rgba(201, 217, 235, 0.52);
}

.tool-title {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 800;
}

.tool-remote {
  margin-top: 4px;
  color: var(--primary-strong);
  font-size: 12px;
  font-weight: 700;
}

.tool-card p {
  margin-top: 8px;
  color: var(--text-secondary);
  line-height: 1.6;
  font-size: 12px;
}

.tool-empty,
.mcp-empty {
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--text-secondary);
}

.tool-empty {
  min-height: 84px;
  border-radius: 16px;
  background: rgba(248, 251, 255, 0.86);
  border: 1px dashed rgba(201, 217, 235, 0.62);
}

.mcp-empty {
  min-height: 420px;
  padding: 20px;
  gap: 10px;
}

.mcp-empty-icon {
  width: 58px;
  height: 58px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(223, 238, 255, 0.98), rgba(236, 245, 255, 0.98));
  color: var(--primary-strong);
  font-size: 22px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.68);
}

.mcp-empty h3 {
  margin: 18px 0 10px;
  font-size: 24px;
  color: var(--text-primary);
}

.mcp-empty p {
  max-width: 480px;
  color: var(--text-secondary);
  line-height: 1.7;
}

.status-pill,
.source-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.source-pill.custom {
  background: rgba(77, 141, 255, 0.16);
  color: #3f7de0;
}

.source-pill.builtin {
  background: rgba(141, 157, 175, 0.16);
  color: #6f8094;
}

.status-pill i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-pill.success {
  background: rgba(30, 200, 165, 0.16);
  color: #119b7f;
}

.status-pill.success i {
  background: #119b7f;
}

.status-pill.warning {
  background: rgba(255, 182, 72, 0.2);
  color: #d68a10;
}

.status-pill.warning i {
  background: #d68a10;
}

.status-pill.info {
  background: rgba(77, 141, 255, 0.18);
  color: #4d8dff;
}

.status-pill.info i {
  background: #4d8dff;
}

.status-pill.danger {
  background: rgba(244, 95, 122, 0.16);
  color: #f45f7a;
}

.status-pill.danger i {
  background: #f45f7a;
}

.status-pill.muted {
  background: rgba(141, 157, 175, 0.16);
  color: #6f8094;
}

.status-pill.muted i {
  background: #6f8094;
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 1100px) {
  .mcp-shell {
    padding: 14px;
  }

  .mcp-board {
    padding: 14px;
  }

  .mcp-hero,
  .mcp-board-header,
  .server-card-top,
  .tool-section-header,
  .server-title-row,
  .server-side {
    flex-direction: column;
    align-items: flex-start;
  }

  .mcp-stats,
  .server-meta-grid {
    grid-template-columns: 1fr;
  }

  .mcp-hero h1 {
    font-size: 20px;
  }

  .server-list {
    grid-template-columns: 1fr;
  }
}
</style>
