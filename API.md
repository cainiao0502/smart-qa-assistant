# 📡 Mini Ragent API 文档

> 后端默认运行在 `http://localhost:8080`

## 通用说明

### 响应格式

所有接口统一返回 `Result<T>` 结构：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": { ... }
}
```

### 错误码

| code | 说明 |
|------|------|
| `SUCCESS` | 请求成功 |
| `BAD_REQUEST` | 参数校验失败或业务逻辑错误 |
| `NOT_FOUND` | 资源不存在 |
| `INTERNAL_ERROR` | 服务器内部错误 |

错误响应示例：

```json
{
  "code": "NOT_FOUND",
  "message": "session not found: abc123",
  "data": null
}
```

---

## 1. 聊天 `/api/chat`

### 1.1 同步聊天

```
POST /api/chat
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sessionId | string | ✅ | 会话ID |
| question | string | ✅ | 用户问题 |
| kbId | long | ❌ | 知识库ID，不传则为通用对话 |
| topK | int | ❌ | 检索返回条数（1-20） |
| scoreThreshold | double | ❌ | 相似度阈值（0.0-1.0） |
| documentIds | long[] | ❌ | 限定检索的文档ID列表 |
| fileTypes | string[] | ❌ | 限定检索的文件类型 |
| documentNameKeyword | string | ❌ | 文档名关键词过滤 |
| skillNames | string[] | ❌ | 启用的Skill名称列表 |
| agentEnabled | boolean | ❌ | 是否启用Agent增强模式 |

**响应体 `data`：**

```json
{
  "runId": "run-abc123",
  "runStatus": "COMPLETED",
  "answer": "这是AI的回答...",
  "answerMode": "agent",
  "retrievalConfig": {
    "topK": 4,
    "scoreThreshold": 0.600,
    "originalQuery": "原始问题",
    "effectiveQuery": "改写后的问题",
    "queryRewritten": true,
    "reranked": false
  },
  "references": [...],
  "toolCalls": [...],
  "agentPlan": [...],
  "currentActionKey": "",
  "completedTaskKeys": [...],
  "agentSteps": [...]
}
```

### 1.2 流式聊天（SSE）

```
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

请求体与同步聊天相同。

**SSE 事件类型：**

| 事件名 | 说明 | data 格式 |
|--------|------|-----------|
| `start` | 流开始 | `{"sessionId":"...","kbId":1}` |
| `ping` | 心跳（每10秒） | `{"ts":1234567890}` |
| `thinking` | 思考内容增量 | `{"delta":"思考片段..."}` |
| `message` | 回答内容增量 | `{"delta":"回答片段..."}` |
| `run_status` | Agent运行状态变更 | `{"runId":"...","status":"RUNNING"}` |
| `agent_step` | Agent执行步骤 | AgentStep对象 |
| `agent_plan` | Agent计划更新 | `{"runId":"...","tasks":[...],"currentActionKey":"...","completedTaskKeys":[...]}` |
| `tool_result` | 工具调用结果 | ToolCallTrace对象 |
| `tool_calls` | 全部工具调用汇总 | ToolCallTrace数组 |
| `references` | 参考来源 | ReferenceChunk数组 |
| `done` | 流结束，包含完整响应 | ChatResponse对象 |
| `error` | 错误 | `{"message":"错误信息"}` |

### 1.3 获取会话消息

```
GET /api/chat/sessions/{sessionId}/messages
```

**响应：** `ChatMessage` 数组

### 1.4 获取会话列表

```
GET /api/chat/sessions
```

**响应：**

```json
[
  {
    "sessionId": "sess-abc",
    "title": "关于部署的问题",
    "preview": "",
    "kbId": 1,
    "kbName": "default_kb",
    "messageCount": 6,
    "lastActivityAt": "2025-01-15T10:30:00"
  }
]
```

### 1.5 删除会话

```
DELETE /api/chat/sessions/{sessionId}
```

### 1.6 获取Agent运行详情

```
GET /api/chat/runs/{runId}
```

**响应：** AgentRunDetail 对象，包含运行状态、步骤列表和最终回答。

---

## 2. 知识库 `/api/kb`

### 2.1 创建知识库

```
POST /api/kb
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | ✅ | 知识库名称 |
| description | string | ❌ | 描述 |

### 2.2 获取知识库列表

```
GET /api/kb
```

**响应：**

```json
[
  {
    "id": 1,
    "name": "default_kb",
    "description": "Mini Ragent default knowledge base",
    "createdAt": "2025-01-01T00:00:00",
    "updatedAt": "2025-01-01T00:00:00"
  }
]
```

### 2.3 删除知识库

```
DELETE /api/kb/{kbId}
```

---

## 3. 文档管理 `/api`

### 3.1 上传文档

```
POST /api/kb/{kbId}/documents/upload
Content-Type: multipart/form-data
```

**参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| file | File | 文档文件（支持 txt、md、pdf） |

**响应：** DocumentResponse 对象

### 3.2 获取文档列表

```
GET /api/kb/{kbId}/documents
```

### 3.3 获取文档详情

```
GET /api/documents/{docId}
```

**响应：** 包含文档原文内容和切片列表。

### 3.4 触发文档索引

```
POST /api/documents/{docId}/index?forceReindex=false
```

异步操作，立即返回任务信息。前端通过轮询 `/api/tasks/{taskId}` 获取进度。

**响应：**

```json
{
  "id": 1,
  "docId": 5,
  "kbId": 1,
  "status": "PENDING",
  "progress": "PENDING",
  "errorMessage": null,
  "createdAt": "2025-01-15T10:00:00",
  "updatedAt": "2025-01-15T10:00:00"
}
```

**任务状态流转：** `PENDING` → `RUNNING` → `SUCCESS` / `FAILED`

**进度阶段：** `PENDING` → `PARSING` → `CHUNKING` → `EMBEDDING` → `INDEXING`

### 3.5 删除文档

```
DELETE /api/documents/{docId}
```

---

## 4. 异步任务 `/api/tasks`

### 4.1 查询任务状态

```
GET /api/tasks/{taskId}
```

前端每 1.5 秒轮询一次，直到 status 为 `SUCCESS` 或 `FAILED`。

---

## 5. MCP Server `/api/mcp`

### 5.1 获取MCP概览

```
GET /api/mcp/servers
```

**响应：**

```json
{
  "enabled": true,
  "requestTimeoutMs": 30000,
  "toolCacheSeconds": 300,
  "serverCount": 2,
  "enabledServerCount": 1,
  "availableServerCount": 1,
  "totalToolCount": 5,
  "servers": [...]
}
```

### 5.2 创建MCP Server

```
POST /api/mcp/servers
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| serverId | string | ✅ | Server唯一标识 |
| enabled | boolean | ❌ | 是否启用（默认true） |
| type | string | ❌ | 传输类型（默认 streamable-http） |
| baseUrl | string | ❌ | HTTP类型的Server地址 |
| apiKey | string | ❌ | API密钥 |
| command | string | ❌ | stdio类型的启动命令 |
| args | string[] | ❌ | 命令参数 |
| workingDirectory | string | ❌ | 工作目录 |
| toolNamePrefix | string | ❌ | 工具名前缀 |
| allowedTools | string[] | ❌ | 允许的工具列表 |
| headers | map | ❌ | HTTP请求头 |
| environment | map | ❌ | 环境变量 |

### 5.3 更新MCP Server

```
PUT /api/mcp/servers/{serverId}
```

请求体同创建。

### 5.4 删除MCP Server

```
DELETE /api/mcp/servers/{serverId}
```

### 5.5 启动MCP Server

```
POST /api/mcp/servers/{serverId}/start
```

### 5.6 停止MCP Server

```
POST /api/mcp/servers/{serverId}/stop
```

---

## 6. Skill `/api/skills`

### 6.1 获取Skill列表

```
GET /api/skills
```

**响应：**

```json
[
  {
    "name": "codeagent",
    "title": "Code Agent",
    "description": "代码生成与分析",
    "toolName": "code_agent",
    "executorType": "llm",
    "executable": true
  }
]
```

### 6.2 获取Skill详情

```
GET /api/skills/{name}
```

### 6.3 创建Skill

```
POST /api/skills
Content-Type: application/json
```

### 6.4 更新Skill

```
PUT /api/skills/{name}
```

### 6.5 删除Skill

```
DELETE /api/skills/{name}
```

### 6.6 导入Skill

```
POST /api/skills/import
Content-Type: multipart/form-data
```

| 参数 | 类型 | 说明 |
|------|------|------|
| file | File | Skill定义文件 |
| name | string | 自定义名称（可选） |
| overwrite | boolean | 是否覆盖同名Skill（默认false） |

### 6.7 执行Skill（调试）

```
POST /api/skills/{name}/execute
Content-Type: application/json
```

**请求体：**

```json
{
  "question": "帮我写一个排序算法",
  "arguments": { "language": "java" }
}
```

**响应：**

```json
{
  "skillName": "codeagent",
  "question": "帮我写一个排序算法",
  "summary": "执行摘要",
  "output": "执行结果",
  "trace": "执行轨迹"
}
```

### 6.8 重新加载Skill

```
POST /api/skills/reload
```

从磁盘重新加载所有Skill定义。
