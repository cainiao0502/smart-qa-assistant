# Mini Ragent

一个从 `RAG Assistant` 持续演进到 `Tool-Use Agent` 的轻量知识库项目。

当前版本已经不是单纯的问答 Demo，而是一套可运行的本地知识库系统，包含：

- 知识库创建、列表、删除
- 文档上传、详情查看、异步入库、删除
- 基于 `PostgreSQL + pgvector` 的向量检索
- 会话聊天、SSE 流式回复、会话历史
- 检索配置：`topK`、阈值、文档范围、文件类型、文件名关键词
- MCP 服务管理：新增 / 编辑 / 删除 / 启动 / 停止 / 工具概览
- Skill 管理：导入 / 新建 / 编辑 / 删除 / 重载 / 调试执行
- Agent Runtime 基础能力：多步执行、步骤记录、运行详情、执行轨迹展示

这个仓库的目标不是做一个“只会调用模型 API 的聊天壳”，而是做一套可继续扩展成 Agent 的私有知识问答系统。

---

## 当前状态

如果用一句话描述当前版本：

> RAG 主链路已经完整打通，MCP、Skill、Agent Runtime 都已有可运行骨架，项目处于“可持续开发和内部试用”阶段。

更具体一点：

- `RAG 主链路`：可用
- `前后端页面`：可用
- `Agent Runtime`：已接入主聊天链路
- `Skill 系统`：已提供后端接口和前端调试页
- `MCP 管理`：已支持本地自定义配置和进程启停
- `工程稳定性`：仍在快速迭代，文档与代码偶尔会出现版本差

---

## 核心能力

### 1. RAG 主链路

项目已经打通从文档到回答的完整链路：

1. 上传文档
2. 解析文档文本
3. 切分 chunk
4. 生成 embedding
5. 写入 pgvector
6. 语义检索召回
7. 组装 Prompt
8. 生成回答并返回引用

### 2. 异步文档入库

文档索引不是阻塞式接口，而是异步任务流程：

- 触发 `/api/documents/{docId}/index`
- 返回任务 ID
- 前端轮询 `/api/tasks/{taskId}`
- 展示 `PARSING / CHUNKING / EMBEDDING / INDEXING` 等阶段

### 3. 流式聊天和执行轨迹

聊天接口同时支持普通返回和 SSE 流式返回：

- `/api/chat`
- `/api/chat/stream`

流式模式下会逐步推送：

- 思考片段
- 消息增量
- Agent plan
- Agent step
- tool result
- references
- done

### 4. MCP 管理

项目已经支持真实 MCP，而不是 mock：

- 支持 `stdio` 类型 MCP
- 支持前端管理自定义 MCP 配置
- 支持前端直接启动 / 停止本地 MCP 进程
- 支持查看服务状态和暴露工具

自定义 MCP 默认保存在：

```text
data/mcp/custom-servers.json
```

### 5. Skill 系统

当前版本已经提供本地 Skill 管理能力：

- 列表与详情
- 导入 `SKILL.md`
- 新建 / 编辑 / 删除
- 重新加载
- 对可执行 Skill 做调试调用

### 6. Agent Runtime

当前主聊天链路已经不是“纯 RAG + 一次性工具调用”，而是带有多步执行骨架的 Agent Runtime：

- 任务规划
- 步骤执行
- tool trace
- 运行详情
- 最终回答与中间执行过程分离

下一步仍然会继续补强：

`Plan -> Act -> Observe -> Replan -> Finish`

---

## 技术栈

### 后端

- Java 21
- Spring Boot 4.x
- MyBatis
- PostgreSQL
- pgvector
- Apache Tika

### 前端

- Vue 3
- Vite
- Element Plus
- marked
- highlight.js

### 模型与 AI 能力

- OpenAI-compatible LLM API
- Embedding API
- MCP tools

---

## 项目结构

```text
ragent-my/
├─ miniagent-backend/     # Spring Boot 后端
├─ miniagent-front/       # Vue 3 前端
├─ data/                  # 本地运行数据（MCP 配置、Skills）
├─ uploads/               # 上传文档存储目录
├─ database.sql           # 数据库初始化脚本
├─ ragent-init.sql        # 额外初始化脚本
├─ 开发文档.md             # 开发背景与设计说明
├─ Agent开发路线图.md      # Agent 演进路线
└─ Agent开发实施计划.md    # 分阶段实施计划
```

---

## 主要页面

- `/`：聊天页
- `/kb`：知识库管理
- `/kb/:kbId/docs`：文档管理
- `/mcp`：MCP 管理
- `/skills`：Skill 管理

---

## 已完成能力

### 知识库

- 创建知识库
- 查看知识库列表
- 删除知识库

### 文档

- 上传文档
- 文档列表
- 文档详情
- 异步入库任务
- 文档删除

### 问答

- 普通聊天
- SSE 流式聊天
- 检索配置
- 引用来源展示
- 工具调用展示
- 会话历史
- Agent 运行详情

### MCP

- 查看 MCP 服务概览
- 查看工具暴露情况
- 新增自定义 MCP
- 编辑自定义 MCP
- 删除自定义 MCP
- 启动 / 停止 MCP 进程

### Skill

- 查看 Skill 列表和详情
- 导入 `SKILL.md`
- 新建 / 编辑 / 删除
- 重新加载
- 调试执行

---

## 仍在推进的方向

### 1. Agent Runtime 持续补强

- planner 稳定性
- observation 结构优化
- 多步循环策略
- 执行轨迹持久化进一步完善

### 2. 知识库认知增强

目标是减少“知识库里明明有文档，但因为没召回到切片就误判为空”的情况，重点继续完善：

- `kb_catalog`
- `document_detail`
- 更稳的工具规划策略

### 3. 输出和交互体验

- 聊天渲染
- 执行轨迹可视化
- 文件引用展示
- 表格 / 代码块呈现

---

## 快速启动

### 1. 准备依赖

你需要先准备：

- JDK 21
- Node.js 20+
- PostgreSQL
- pgvector 扩展

### 2. 初始化数据库

先执行：

```sql
database.sql
```

如需补充初始化内容，再执行：

```sql
ragent-init.sql
```

### 3. 配置环境变量

复制：

```bash
.env.example
```

另存为：

```bash
.env
```

至少需要关注这些配置：

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/ragent
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password

APP_LLM_BASE_URL=your_llm_base_url
APP_LLM_API_KEY=your_llm_api_key
APP_LLM_CHAT_MODEL=your_chat_model
APP_LLM_EMBEDDING_MODEL=your_embedding_model

SILICONFLOW_API_KEY=your_embedding_api_key

APP_MCP_ENABLED=true
```

### 4. 启动后端

```bash
cd miniagent-backend
./mvnw spring-boot:run
```

Windows：

```powershell
cd miniagent-backend
.\mvnw.cmd spring-boot:run
```

默认地址：

```text
http://localhost:8080
```

### 5. 启动前端

```bash
cd miniagent-front
npm install
npm run dev
```

默认地址通常为：

```text
http://localhost:5173
```

---

## MCP 配置说明

当前推荐使用“前端管理自定义 MCP”为主。

你可以：

- 在前端 MCP 管理页直接新增
- 或手动编辑：

```text
data/mcp/custom-servers.json
```

一个典型的 `stdio` MCP 配置示例：

```json
{
  "type": "stdio",
  "command": "uvx",
  "args": ["mcp-server-fetch"],
  "toolNamePrefix": "fetch"
}
```

---

## 配套文档

- [开发文档.md](./开发文档.md)
- [Agent开发路线图.md](./Agent开发路线图.md)
- [Agent开发实施计划.md](./Agent开发实施计划.md)

其中：

- `开发文档.md` 偏项目背景、技术选型和历史演进
- `Agent开发路线图.md` 偏架构演进方向
- `Agent开发实施计划.md` 偏按阶段落地

---

## 说明

当前仓库仍在快速迭代中，提交到远端前请务必检查：

- `.env`
- API Key
- `data/mcp/custom-servers.json`
- `uploads/`
- 本地运行日志

避免把本地敏感配置和调试产物一起推上去。
