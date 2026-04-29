# Mini Ragent

一个正在从 `RAG Assistant` 演进到 `Tool-Use Agent` 的轻量知识库项目。

当前项目已经具备：

- 知识库创建与管理
- 文档上传、解析、切片、向量化入库
- 基于 `PostgreSQL + pgvector` 的语义检索
- 聊天问答与会话历史
- MCP 工具接入与调用
- 前端可视化 MCP 管理（新增 / 编辑 / 删除 / 启动 / 停止）
- Agent 化演进的基础能力（工具规划、工具执行、轨迹展示雏形）

这个仓库的目标不是做一个“只会调模型 API 的聊天壳”，而是做一套能真正落地、能继续演进成 Agent 的私有知识问答系统。

---

## 项目特点

### 1. RAG 主链路完整

项目已经打通了从文档到回答的完整链路：

1. 上传文档
2. 解析文档文本
3. 切分 chunk
4. 生成 embedding
5. 写入 pgvector
6. 语义检索召回
7. 组装 Prompt
8. 生成最终回答

### 2. 支持真实 MCP

项目不是 mock 工具调用，而是已经支持真实 MCP server：

- 支持 `stdio` 类型 MCP
- 支持前端管理自定义 MCP
- 支持前端启动 / 停止 MCP 进程
- 支持 MCP 工具参与回答过程

### 3. 前端体验不是脚手架默认页

前端已经做过多轮重构，当前方向是：

- 浅色、简洁、偏 Apple 风格
- 可收缩侧边栏
- 聊天气泡与 Markdown 渲染优化
- 工具调用 / 引用来源展示
- MCP 管理页可直接运维

### 4. 正在向 Agent 架构升级

当前版本已经不再是纯 RAG Demo，而是具备 Agent 雏形：

- 会做工具规划
- 会调用 MCP / KB 工具
- 会将工具结果并入最终回答

下一阶段会继续升级为真正的多步 Agent：

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
├─ data/                  # 本地运行数据（含自定义 MCP 配置）
├─ uploads/               # 上传文档存储目录
├─ database.sql           # 数据库初始化脚本
├─ ragent-init.sql        # 额外初始化脚本
├─ 开发文档.md             # 项目开发文档

```

---

## 当前已完成功能

### 知识库

- 创建知识库
- 知识库列表
- 删除知识库

### 文档

- 文档上传
- 文档列表
- 文档详情
- 异步入库
- 文档删除

### RAG 问答

- 会话聊天
- 检索配置
- 引用来源展示
- 工具调用展示
- 会话历史

### MCP 管理

- 查看 MCP 列表
- 查看工具暴露情况
- 前端新增自定义 MCP
- 前端编辑自定义 MCP
- 前端删除自定义 MCP
- 前端启动 / 停止 MCP
- 自定义 MCP 配置持久化

自定义 MCP 默认保存位置：

```text
data/mcp/custom-servers.json
```

---

## 当前仍在推进的方向

### 1. Agent Runtime

计划升级为最小可落地 Agent 架构：

- 多步循环执行
- step 记录
- observation 组装
- 最终回答分离

### 2. 知识库认知增强

目标是解决“知识库明明有文档，但因为没召回到切片就误判为空”的问题，计划补齐：

- `kb_catalog`
- `document_detail`

### 3. 输出格式优化

持续优化聊天渲染，让：

- 文件引用更明确
- 链接更清晰
- 代码块更像代码
- 表格更分明

---

## 快速启动

## 1. 准备依赖

你需要先准备：

- JDK 21
- Node.js 18+
- PostgreSQL
- pgvector 扩展

---

## 2. 初始化数据库

先执行仓库根目录下的 SQL：

```sql
database.sql
```

如果你本地还需要额外初始化内容，再执行：

```sql
ragent-init.sql
```

---

## 3. 配置环境变量

复制一份：

```bash
.env.example
```

改成：

```bash
.env
```

至少需要关注这些配置：

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/ragent
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

APP_LLM_BASE_URL=your_llm_base_url
APP_LLM_API_KEY=your_llm_api_key
APP_LLM_CHAT_MODEL=your_chat_model
APP_LLM_EMBEDDING_MODEL=your_embedding_model

SILICONFLOW_API_KEY=your_embedding_api_key
```

如果要启用 MCP：

```env
APP_MCP_ENABLED=true
```

---

## 4. 启动后端

```bash
cd miniagent-backend
./mvnw spring-boot:run
```

Windows：

```powershell
cd miniagent-backend
.\mvnw.cmd spring-boot:run
```

默认端口：

```text
http://localhost:8080
```

---

## 5. 启动前端

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

当前项目已经统一切换到“自定义 MCP 管理”为主。

你可以：

- 在前端 MCP 管理页直接新增
- 或者手动编辑：

```text
data/mcp/custom-servers.json
```

一个典型的 `stdio` MCP 配置如下：

```json
{
  "type": "stdio",
  "command": "uvx",
  "args": [
    "minimax-coding-plan-mcp",
    "-y"
  ],
  "env": {
    "MINIMAX_API_HOST": "https://api.minimaxi.com",
    "MINIMAX_API_KEY": "your_api_key"
  },
  "toolNamePrefix": "minimax"
}
```

---

## 开发文档

仓库里已经有两份更偏内部推进的文档：

- [开发文档.md](./开发文档.md)
- [Agent开发路线图.md](./Agent开发路线图.md)

其中：

- `开发文档.md` 更偏整体项目背景和现有方案
- `Agent开发路线图.md` 更偏下一阶段的 Agent 架构落地路线

---

## 当前定位

如果用一句话描述这个项目：

> 这是一个已经打通 RAG 主链路，并且正在往真实 Tool-Use Agent 升级的轻量知识库系统。

它不是成熟商业产品，但已经超过了普通 Demo 的阶段，适合作为：

- AI 应用项目
- RAG 项目实践
- MCP 接入实践
- Agent 架构演进样例

---

## 后续计划

接下来优先级最高的工作：

1. Agent Runtime 多步执行骨架
2. `kb_catalog` / `document_detail` 工具
3. 执行轨迹持久化
4. skill 接入

---

## 说明

当前仓库仍在快速迭代中，部分文案、样式和交互还会继续调整。

如果你准备把它公开到 GitHub，建议在提交前再检查一次：

- `.env`
- 数据库密码
- API Key
- `data/mcp/custom-servers.json`

避免把本地敏感配置一起推上去。

