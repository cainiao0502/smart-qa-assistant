# 🤖 Mini Ragent

一个基于 Java 21 + Spring Boot + Vue 3 的 🤖 Agent/RAG 项目，目标不是做"再一个聊天壳" 🥱，而是把知识库检索、工具调用、流式回答和多步 Agent Runtime 串成一条可运行、可讲清楚的主链路 🚀

适合作为个人项目展示的重点在于两件事：

1. 🔄 它不只做单次 LLM 调用，而是实现了 `Plan -> Act -> Observe -> Replan -> Finish` 的执行闭环。
2. 🖥️ 它不只停留在后端接口，而是补齐了文档入库、SSE 聊天、MCP 管理、Skill 调试和运行轨迹展示。

## 🎯 项目定位

这个项目最初从一个精简版 RAG 系统起步 📚，后续逐步扩展到了 Agent 化执行 🤖。当前版本重点解决的是：

- 📤 文档上传、解析、切分、向量化、检索
- 💬 基于知识库的多轮聊天
- 📡 SSE 流式回答
- 🔌 MCP Server 的配置、启停和工具调用
- 🧩 Skill 的注册、管理和调试执行
- 🔁 Agent Runtime 的多步循环、轨迹记录和最终回答分离

如果你要把它写进简历 📝，比较合适的表述是：

> 独立实现一个 Java Agent 系统 🤖，支持 RAG 检索、SSE 流式聊天、MCP 工具调用、Skill 调试与多步 Agent Runtime，并对运行状态一致性和失败场景做了稳定性处理 🛡️。

## ⚡ 核心能力

### 🤖 1. Agent Runtime

- 🔁 多步执行循环，按步骤进行规划、调用工具、观察结果和重新规划
- 💾 支持运行记录与步骤记录持久化
- 📝 将"执行过程"和"最终回答"解耦，降低中间推理对输出的污染
- 🛡️ 已补充聊天主链路失败场景处理，避免运行状态错误地停留在成功态

### 📚 2. RAG 主链路

- 📤 支持 `txt`、`md`、`pdf` 等文档上传
- 📄 使用 Apache Tika 提取文本
- ✂️ 文档切分后写入 PostgreSQL + pgvector
- 🔍 支持基于语义检索的问答和引用来源返回
- ⏳ 异步索引入库，便于观察文档处理进度

### 💬 3. 聊天与流式输出

- 📦 支持会话消息存储
- 📡 支持 SSE 流式聊天
- 🔧 支持在聊天过程中展示工具调用和中间执行轨迹
- 🛡️ 对流式回答中断、部分输出等情况做了状态回写和数据兜底

### 🔌 4. MCP 与 Skill

- 🛠️ 支持 stdio 类型 MCP Server 的真实启动与停止
- 📋 支持 MCP 工具目录发现与调用
- 🧩 支持 Skill 注册、管理和调试执行
- 🖥️ 前端提供独立页面进行运维和验证

## 🛠️ 技术栈

### ☕ 后端

- ☕ Java 21
- � Spring Boot 4.x
- �️ MyBatis
- 🐘 PostgreSQL
- 🧮 pgvector
- 📄 Apache Tika

### 🖼️ 前端

- 🖼️ Vue 3
- ⚡ Vite
- 🎨 Element Plus
- � marked
- 🎨 highlight.js

### 🤖 AI 与工具链

- 🤖 OpenAI 兼容聊天/Embedding 接口
- 🔧 MCP 工具调用
- 🧩 本地 Skill 注册与调试

## 🏗️ 系统结构

```text
ChatServiceImpl
  -> ChatAgentOrchestrator
  -> AgentRuntimeService
       -> AgentPlannerService
       -> ToolExecutorRegistry
            -> kb_lookup / kb_catalog / document_detail
            -> MCP tools
            -> Skills
       -> ObservationBuilder
       -> AgentRunStore
  -> FinalAnswerComposer
```

核心设计点：

- 🎯 `ChatServiceImpl` 负责聊天入口、消息保存和流式输出
- 🔁 `AgentRuntimeService` 负责多步运行主循环
- 🧠 `AgentPlannerService` 负责下一步动作决策
- 🔧 `ToolExecutorRegistry` 负责统一工具分发
- 📝 `FinalAnswerComposer` 负责把执行结果整理为最终用户回答

## 🖥️ 前端页面

- 💬 `ChatView`：聊天、流式回答、工具调用展示、引用展示
- 📁 `DocumentView`：文档上传、列表、异步索引状态
- 📚 `KnowledgeBaseView`：知识库管理
- 🔌 `McpView`：MCP Server 配置、启停、工具发现
- 🧩 `SkillView`：Skill 管理和调试执行

## 📁 目录结构

```text
ragent-my/
├─ miniagent-backend/
│  └─ src/main/java/com/nailinai/miniagentbackend/
│     ├─ agent/        🤖 Agent Runtime、Planner、Tool Registry、Run Store
│     ├─ controller/   🌐 REST API
│     ├─ service/      ⚙️ 聊天、检索、文档处理等业务逻辑
│     ├─ mcp/          🔌 MCP 客户端与配置管理
│     ├─ skill/        🧩 Skill 注册与执行
│     └─ entity/       📦 数据实体
├─ miniagent-front/
│  └─ src/
│     ├─ views/        🖥️ 主要页面
│     ├─ api/          🔗 前端接口封装
│     └─ components/   🧩 公共组件
├─ data/mcp/           🔌 本地 MCP 配置
├─ uploads/            📤 上传文档存储
├─ database.sql        🐘 数据库初始化脚本
└─ .env.example        🔑 环境变量模板
```

## 🚀 快速启动

### 1️⃣ 环境准备

- ☕ JDK 21
- 📦 Node.js 18+
- 🐘 PostgreSQL
- 🧮 pgvector 扩展

### 2️⃣ 初始化数据库

```bash
psql -U postgres -d ragent -f database.sql
```

### 3️⃣ 配置环境变量

在项目根目录复制模板：

```bash
cp .env.example .env
```

至少需要配置：

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/ragent
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password

APP_LLM_BASE_URL=your_llm_base_url
APP_LLM_API_KEY=your_llm_api_key
APP_LLM_CHAT_MODEL=your_chat_model
APP_LLM_EMBEDDING_MODEL=your_embedding_model
```

如果需要启用 MCP 🔌，再打开相关配置：

```env
APP_MCP_ENABLED=true
```

### 4️⃣ 启动后端

```bash
cd miniagent-backend
./mvnw spring-boot:run
```

Windows:

```bash
.\mvnw.cmd spring-boot:run
```

默认端口：`http://localhost:8080`

### 5️⃣ 启动前端

```bash
cd miniagent-front
npm install
npm run dev
```

默认地址：`http://localhost:5173`

## 🎬 推荐演示链路

如果你要拿这个项目去面试 🎓，建议现场演示或录屏按下面顺序来：

1. 📚 创建知识库
2. 📤 上传文档并观察异步索引状态
3. 💬 在聊天页发起问题，展示检索增强回答
4. 📡 演示 SSE 流式输出
5. 🔌 在 MCP 页启动一个 Server 并调用工具
6. 🧩 在 Skill 页执行一次 Skill 调试
7. 🤖 展示 Agent 运行轨迹或工具调用过程

## 📊 当前完成度

这个项目已经不是纯 Demo 🚫，而是一个能完整展示 AI 应用工程能力的个人项目 ✅。当前更偏向"功能完整、还在持续打磨稳定性"的阶段，适合用于：

- ☕ Java 后端实习
- 🤖 AI 应用工程 / Agent 工程方向实习
- 🎓 需要展示工程落地能力的课程或面试项目

如果要继续提升展示效果 📈，优先建议补三件事：

1. 📸 增加 README 演示截图
2. 📊 补一页接口/模块时序图
3. 🧪 继续完善聊天主链路和 Agent Runtime 的异常场景测试

## 🧪 测试

后端已经补充了主链路相关测试，可在 `miniagent-backend` 下运行：

```bash
.\mvnw.cmd test
```

如果只跑聊天链路相关测试：

```bash
.\mvnw.cmd -Dtest=ChatServiceImplTest test
```

## ⚠️ 说明

- 🏗️ 这是一个个人项目，当前更关注主链路闭环、架构表达和可讲性
- 🔒 本地运行时配置文件和密钥不应提交到公开仓库
- 📖 某些设计文档记录的是早期 MVP 方案，和当前实现会有差异，阅读时请以当前代码和本 README 为准
