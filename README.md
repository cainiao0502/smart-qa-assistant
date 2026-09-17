# 🤖 Mini Ragent

一个基于 Java 21 + Spring Boot 4 + Vue 3 的 🤖 Agent/RAG 项目，目标不是做"再一个聊天壳" 🥱，而是把知识库检索、工具调用、流式回答和多步 Agent Runtime 串成一条可运行、可讲清楚的主链路 🚀

适合作为个人项目展示的重点在于两件事：

1. 🔄 它不只做单次 LLM 调用，而是实现了 `Plan -> Act -> Observe -> Replan -> Finish` 的执行闭环。
2. 🖥️ 它不只停留在后端接口，而是补齐了文档入库、SSE 聊天、MCP 管理、Skill 调试、用户认证和运行轨迹展示。

## 🎯 项目定位

这个项目最初从一个精简版 RAG 系统起步 📚，后续逐步扩展到了 Agent 化执行 🤖。当前版本重点解决的是：

- 📤 文档上传、解析、切分、向量化、检索
- 💬 基于知识库的多轮聊天（含会话级并发串行化保护）
- 📡 SSE 流式回答（心跳、断线检测、部分输出兜底）
- 🔌 MCP Server 的配置、启停和工具调用
- 🧩 Skill 的注册、管理和调试执行
- 🔁 Agent Runtime 的多步循环、轨迹记录和最终回答分离
- 🔐 用户注册/登录（sa-token + BCrypt）与会话隔离
- 🛡️ 输入护栏（Guardrail）：长度限制 + Prompt 注入检测，请求进入 LLM 链路前拦截
- 📊 RAG 检索质量评估：评估集 + recall@k / precision@k + 查询改写/重排消融对比

如果你要把它写进简历 📝，比较合适的表述是：

> 独立实现一个 Java Agent 系统 🤖，支持 RAG 检索、SSE 流式聊天、MCP 工具调用、Skill 调试与多步 Agent Runtime，并对运行状态一致性、会话并发和失败场景做了稳定性处理 🛡️。

## ⚡ 核心能力

### 🤖 1. Agent Runtime

- 🔁 多步执行循环，按步骤进行规划、调用工具、观察结果和重新规划（最大步数可配置，默认 6）
- 🛡️ planner_guard 保护：Planner 试图在非最终任务未完成时提前结束会被拦截，强制继续或转为信息缺口响应
- 💾 支持运行记录与步骤记录持久化（agent_run / agent_step 表）
- 📝 将"执行过程"和"最终回答"解耦，降低中间推理对输出的污染
- 🛡️ 工具执行失败自动降级为 PARTIAL 状态，不中断整轮对话；支持取消信号

### 📚 2. RAG 主链路

- 📤 支持 `txt`、`md`、`pdf` 等文档上传（Apache Tika 提取）
- ✂️ 固定大小 + 语义切分两种策略，写入 PostgreSQL + pgvector（HNSW 索引）
- 🔍 混合检索：关键词通道 + 向量通道 → 去重 → Rerank（语义 + 词汇加权）
- ✏️ 查询改写（query rewrite），提升检索召回
- ⏳ 异步索引入库（PARSING → CHUNKING → EMBEDDING → INDEXING 分阶段进度追踪）
- 📊 检索质量可量化：`data/eval/eval-set.json` 评估集 + 文档级指标（recall@k / precision@k）+ 语义级指标（LLM-as-judge：faithfulness / answerRelevancy）+ 查询改写/重排四组合消融对比

### 💬 3. 聊天与流式输出

- 📦 会话消息持久化 + 会话列表/删除
- 📡 SSE 流式聊天：`thinking` / `message` / `agent_step` / `tool_result` / `references` / `done` 全事件协议，10 秒心跳
- 🔐 会话级并发控制：同一会话同时发起的消息会被拒绝（CONFLICT），保证上下文串行一致
- 🧮 上下文窗口管理：在条数截断之上增加 token 预算控制，从最旧消息裁剪并保证最近多轮连续
- 🛡️ 输入护栏：超长/空问题与 Prompt 注入句式在进入 LLM 前被拦截（可配置开关）
- 🛡️ 对流式回答中断、部分输出等情况做了状态回写和数据兜底

### 🔌 4. MCP 与 Skill

- 🛠️ 支持 stdio 类型 MCP Server 的真实启动与停止（ProcessBuilder + JSON-RPC + 进程树销毁）
- 📋 支持 MCP 工具目录发现与调用（工具缓存、超时控制）
- 🧩 支持 Skill 注册、管理和调试执行（提示词注入型）
- 🖥️ 前端提供独立页面进行运维和验证

### 🔐 5. 认证与多模型

- 🔑 sa-token + BCrypt 注册/登录/登出，Bearer Token，30 天会话
- 🌐 多模型路由：minimax / siliconflow 双候选 + 健康检查 + 3 次重试

## 🛠️ 技术栈

### ☕ 后端

- ☕ Java 21
- ⚡ Spring Boot 4.x（多模块：framework / infra-ai / bootstrap）
- 🗄️ MyBatis + PostgreSQL + pgvector（HNSW）
- 🔄 sa-token + Redis（认证与会话存储）
- 📄 Apache Tika
- 🧪 JUnit 5 + Mockito + AssertJ

### 🖼️ 前端

- 🖼️ Vue 3 + Vite + Element Plus
- 📝 marked + highlight.js + mermaid
- ✨ GSAP 动效

### 🤖 AI 与工具链

- 🤖 OpenAI 兼容聊天/Embedding 接口（多供应商路由）
- 🔧 MCP 工具调用
- 🧩 本地 Skill 注册与调试

## 🏗️ 系统结构

```text
ChatServiceImpl (会话锁 -> 意图路由)
  -> ChatAgentOrchestrator
  -> AgentRuntimeService
       -> AgentPlannerService（LLM 决策 + planner_guard 防过早终止）
       -> ToolExecutorRegistry
            -> kb_lookup / kb_catalog / document_detail
            -> MCP tools
            -> Skills
       -> ObservationBuilder
       -> AgentRunStore
  -> FinalAnswerComposer
```

模块划分：

```text
ragent-parent/
├─ ragent-framework/   🧱 通用框架：Result/异常体系/JSON/Token 预估
├─ ragent-infra-ai/    🤖 AI 基础设施：Chat/Embedding 客户端、多模型路由
└─ ragent-bootstrap/   🚀 主应用：agent、chat、kb、document、mcp、skill、user
```

## 🖥️ 前端页面

- 💬 `ChatView`：聊天、流式回答、工具调用展示、引用展示
- 📁 `DocumentView`：文档上传、列表、异步索引状态
- 📚 `KnowledgeBaseView`：知识库管理
- 🔌 `McpView`：MCP Server 配置、启停、工具发现
- 🧩 `SkillView`：Skill 管理和调试执行
- 👤 `LoginView` / `UserCenterView`：登录注册与个人中心

## 🚀 快速启动

### 1️⃣ 环境准备

- ☕ JDK 21
- 📦 Node.js 18+
- 🐘 PostgreSQL + pgvector 扩展
- 🍃 Redis

### 2️⃣ 初始化数据库

```bash
psql -U postgres -d ragent -f database.sql
```

（脚本包含知识库/文档/切片/会话/Agent 运行/任务/用户 8 张表）

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

如需启用 MCP 🔌：

```env
APP_MCP_ENABLED=true
```

### 4️⃣ 启动后端

```bash
cd ragent-bootstrap
mvn spring-boot:run
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

1. 🔐 注册/登录
2. 📚 创建知识库
3. 📤 上传文档并观察异步索引状态
4. 💬 在聊天页发起问题，展示检索增强回答
5. 📡 演示 SSE 流式输出
6. 🔌 在 MCP 页启动一个 Server 并调用工具
7. 🧩 在 Skill 页执行一次 Skill 调试
8. 🤖 展示 Agent 运行轨迹或工具调用过程

## 🧪 测试

后端已补充核心链路单元测试（JUnit 5 + Mockito + AssertJ），覆盖：

- `AgentRuntimeServiceTest`：多步循环、工具调用闭环、失败降级、取消信号、最大步数兜底、过早终止保护
- `AgentPlannerServiceTest`：决策解析、空响应降级、非法 JSON 重试、document_detail 参数修复
- `IntentClassifierTest`：规则直判与 LLM 意图路由、CLARIFY 默认文案、异常兜底
- `MultiChannelRetrieverTest`：多通道聚合、通道故障隔离、后处理器顺序
- `ContextWindowManagerTest`：token 预算裁剪、最近消息保留下限
- `RagEvaluatorTest`：recall@k / precision@k 计算、文档名匹配、多查询汇总
- `RagSemanticEvaluatorTest`：LLM-as-judge 打分解析、非法响应降级、分数钳制
- `GuardrailManagerTest`：长度限制、Prompt 注入检测（中英文）、规则短路、可开关
- `ChatServiceImplTest`：护栏/并发锁拦截、CLARIFY/DIRECT/AGENT 分支、编排失败状态回写、锁释放

运行方式：

```bash
mvn -pl ragent-bootstrap test
```

> 提示：首次构建前先 `mvn -pl ragent-framework,ragent-infra-ai install`（或直接 `mvn -pl ragent-bootstrap -am test`），保证依赖模块使用当前源码而非本地仓库旧版本。

### RAG 评估（可选）

准备评估集 `data/eval/eval-set.json`（问题 + 期望命中文档），启动后端时开启：

```bash
APP_EVAL_ENABLED=true mvn -pl ragent-bootstrap -am spring-boot:run
```

启动日志会输出：
- 查询改写/重排四组合消融的文档级 recall@k / precision@k 对比；
- 若再开启 `APP_EVAL_SEMANTIC_ENABLED=true`，追加语义级评估：对每条查询基于检索切片生成回答，用 LLM-as-judge 打分 faithfulness（忠实度/幻觉程度）与 answerRelevancy（相关性）并输出均值。

## 📊 当前完成度

这个项目已经不是纯 Demo 🚫，而是一个能完整展示 AI 应用工程能力的个人项目 ✅。当前更偏向"功能完整、主链路有测试、持续打磨稳定性"的阶段，适合用于：

- ☕ Java 后端实习
- 🤖 AI 应用工程 / Agent 工程方向实习
- 🎓 需要展示工程落地能力的课程或面试项目

如果要继续提升展示效果 📈，优先建议补三件事：

1. 📸 增加 README 演示截图
2. 📊 补一页接口/模块时序图
3. 🧪 继续完善聊天主链路和 Agent Runtime 的异常场景测试

## ⚠️ 说明

- 🏗️ 这是一个个人项目，当前更关注主链路闭环、架构表达和可讲性
- 🔒 本地运行时配置文件和密钥不应提交到公开仓库（`.env`、`data/mcp/custom-servers.json` 已在 `.gitignore`）
- 📖 某些设计文档记录的是早期 MVP 方案，和当前实现会有差异，阅读时请以当前代码和本 README 为准
