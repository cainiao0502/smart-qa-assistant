package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.agent.ChatAgentOrchestrator;
import com.nailinai.ragent.agent.FinalAnswerComposer;
import com.nailinai.ragent.agent.AgentRunStore;
import com.nailinai.ragent.agent.AgentRuntimeListener;
import com.nailinai.ragent.agent.ApprovalOutcome;
import com.nailinai.ragent.agent.ApprovalRegistry;
import com.nailinai.ragent.user.context.UserIdHolder;
import com.nailinai.ragent.chat.guardrail.GuardrailManager;
import com.nailinai.ragent.chat.guardrail.GuardrailResult;
import com.nailinai.ragent.chat.intent.IntentClassifier;
import com.nailinai.ragent.chat.intent.IntentDecision;
import com.nailinai.ragent.chat.intent.IntentRouter;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.entity.KnowledgeBase;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.SessionSummaryResponse;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.ChatService;
import com.nailinai.ragent.chat.service.ChunkOptimizer;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.chat.service.MemoryService;
import com.nailinai.ragent.framework.util.JsonUtils;
import com.nailinai.ragent.util.CitationValidator;
import com.nailinai.ragent.util.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final MemoryService memoryService;
    private final ChatAgentOrchestrator chatAgentOrchestrator;
    private final FinalAnswerComposer finalAnswerComposer;
    private final AgentRunStore agentRunStore;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentClassifier intentClassifier;
    private final IntentRouter intentRouter;
    private final SessionExecutionGuard sessionExecutionGuard;
    private final ContextWindowManager contextWindowManager;
    private final GuardrailManager guardrailManager;
    private final CitationValidator citationValidator;
    private final ScheduledExecutorService sseHeartbeatExecutor;
    private final int topK;
    private final int historyLimit;
    private final double defaultScoreThreshold;
    /** 大 chunk 拆片限速（打字机演示效果）：默认关闭，LLM 流式直发；见 ChunkOptimizer 类注释 */
    private final boolean chunkSplitEnabled;

    /**
     * 待决审批注册表。用 setter 注入并允许缺省：单元测试里直接 new 时不具备审批通道，
     * 审批策略会按「问不到人」处理——这正是期望的降级行为，而不是让测试意外阻塞。
     */
    private ApprovalRegistry approvalRegistry;

    /** 人工确认的等待上限；超时按未授权处理，绝不放行 */
    private long toolApprovalTimeoutMs = 120000L;

    @Autowired(required = false)
    public void setApprovalRegistry(ApprovalRegistry approvalRegistry) {
        this.approvalRegistry = approvalRegistry;
    }

    @Value("${app.agent.tool-approval.approval-timeout-ms:120000}")
    public void setToolApprovalTimeoutMs(long toolApprovalTimeoutMs) {
        this.toolApprovalTimeoutMs = Math.max(5000L, toolApprovalTimeoutMs);
    }

    public ChatServiceImpl(MemoryService memoryService,
                           ChatAgentOrchestrator chatAgentOrchestrator,
                           FinalAnswerComposer finalAnswerComposer,
                           AgentRunStore agentRunStore,
                           PromptBuilder promptBuilder,
                           ChatClient chatClient,
                           ChatMessageMapper chatMessageMapper,
                           KnowledgeBaseMapper knowledgeBaseMapper,
                           IntentClassifier intentClassifier,
                           IntentRouter intentRouter,
                           SessionExecutionGuard sessionExecutionGuard,
                           ContextWindowManager contextWindowManager,
                           GuardrailManager guardrailManager,
                           CitationValidator citationValidator,
                           ScheduledExecutorService sseHeartbeatExecutor,
                           @Value("${app.rag.top-k}") int topK,
                           @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold,
                           @Value("${app.rag.history-limit}") int historyLimit,
                           @Value("${app.chat.chunk-optimizer.split-enabled:false}") boolean chunkSplitEnabled) {
        this.memoryService = memoryService;
        this.chatAgentOrchestrator = chatAgentOrchestrator;
        this.finalAnswerComposer = finalAnswerComposer;
        this.agentRunStore = agentRunStore;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.chatMessageMapper = chatMessageMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.intentClassifier = intentClassifier;
        this.intentRouter = intentRouter;
        this.sessionExecutionGuard = sessionExecutionGuard;
        this.contextWindowManager = contextWindowManager;
        this.guardrailManager = guardrailManager;
        this.citationValidator = citationValidator;
        this.sseHeartbeatExecutor = sseHeartbeatExecutor;
        this.topK = topK;
        this.historyLimit = historyLimit;
        this.chunkSplitEnabled = chunkSplitEnabled;
        this.defaultScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        GuardrailResult guardrail = guardrailManager.validate(request);
        if (!guardrail.allowed()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, guardrail.reason());
        }
        if (!sessionExecutionGuard.tryAcquire(request.getSessionId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前会话正在处理中，请稍后再试");
        }
        try {
            return doChat(request);
        } finally {
            sessionExecutionGuard.release(request.getSessionId());
        }
    }

    private ChatResponse doChat(ChatRequest request) {
        memoryService.saveUserMessage(request.getSessionId(), request.getKbId(), request.getQuestion());
        List<ChatMessage> history = contextWindowManager.trimHistory(
                memoryService.getRecentMessages(request.getSessionId(), historyLimit), 2);

        IntentDecision decision = intentClassifier.classify(request);
        IntentRouter.RoutingOutcome outcome = intentRouter.route(decision, request, history);

        if (outcome.mode() == IntentRouter.Mode.CLARIFY) {
            String clarification = outcome.clarification();
            memoryService.saveAssistantMessage(
                    request.getSessionId(),
                    request.getKbId(),
                    null,
                    clarification,
                    JsonUtils.toJson(List.of()),
                    JsonUtils.toJson(List.of())
            );
            return ChatResponse.builder()
                    .answer(clarification)
                    .answerMode("clarify")
                    .clarification(clarification)
                    .references(List.of())
                    .toolCalls(List.of())
                    .agentPlan(List.of())
                    .completedTaskKeys(List.of())
                    .agentSteps(List.of())
                    .build();
        }

        if (outcome.mode() == IntentRouter.Mode.DIRECT) {
            String answer = directChat(history, request.getQuestion(), request.getSkillNames());
            memoryService.saveAssistantMessage(
                    request.getSessionId(),
                    request.getKbId(),
                    null,
                    answer,
                    JsonUtils.toJson(List.of()),
                    JsonUtils.toJson(List.of())
            );
            return ChatResponse.builder()
                    .answer(answer)
                    .answerMode("chat")
                    .references(List.of())
                    .toolCalls(List.of())
                    .agentPlan(List.of())
                    .completedTaskKeys(List.of())
                    .agentSteps(List.of())
                    .build();
        }

        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = outcome.orchestration();
        try {
            // 引用真实性校验：模型可能编造 references 里不存在的「片段#N」引用，
            // 交给用户前剔除（同步路径的修正对用户完全可见）
            String answer = citationValidator.validateAndFix(
                    finalAnswerComposer.composeAnswer(orchestration.getFinalPrompt()),
                    orchestration.getReferences());
            agentRunStore.completeRun(orchestration.getRunId(), orchestration.getRunStatus(), answer,
                    orchestration.getUsage());

            memoryService.saveAssistantMessage(
                    request.getSessionId(),
                    request.getKbId(),
                    orchestration.getRunId(),
                    answer,
                    JsonUtils.toJson(orchestration.getReferences()),
                    JsonUtils.toJson(sanitizeToolCalls(orchestration.getToolCalls()))
            );

            return ChatResponse.builder()
                    .runId(orchestration.getRunId())
                    .runStatus(orchestration.getRunStatus())
                    .answer(answer)
                    .answerMode(orchestration.getAnswerMode())
                    .retrievalConfig(buildRetrievalConfig(request, orchestration.getRetrievalResult()))
                    .references(orchestration.getReferences())
                    .toolCalls(orchestration.getToolCalls())
                    .agentPlan(orchestration.getAgentPlan())
                    .currentActionKey(orchestration.getCurrentActionKey())
                    .completedTaskKeys(orchestration.getCompletedTaskKeys())
                    .agentSteps(orchestration.getAgentSteps())
                    .build();
        } catch (RuntimeException exception) {
            agentRunStore.completeRun(orchestration.getRunId(), "FAILED", null);
            throw exception;
        }
    }

    @Override
    public void streamChat(ChatRequest request, SseEmitter emitter) {
        GuardrailResult guardrail = guardrailManager.validate(request);
        if (!guardrail.allowed()) {
            sendEvent(emitter, "error", Map.of("message", guardrail.reason()));
            emitter.complete();
            return;
        }
        if (!sessionExecutionGuard.tryAcquire(request.getSessionId())) {
            sendEvent(emitter, "error", Map.of("message", "当前会话正在处理中，请稍后再试"));
            emitter.complete();
            return;
        }
        try {
            streamChatInternal(request, emitter);
        } finally {
            sessionExecutionGuard.release(request.getSessionId());
        }
    }

    private void streamChatInternal(ChatRequest request, SseEmitter emitter) {
        memoryService.saveUserMessage(request.getSessionId(), request.getKbId(), request.getQuestion());
        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = null;
        StringBuilder answerBuilder = new StringBuilder();
        StreamState streamState = new StreamState();
        AtomicBoolean cancelSignal = new AtomicBoolean(false);
        // 审批的归属校验要用发起人 id。取的是 Controller 在请求线程捕获、再绑定到异步线程上的值：
        // 本方法已运行在异步线程里，直接读 Sa-Token 的 ThreadLocal 必然失败
        // （UserIdHolder.get() 只读绑定值/回退 capture，不会抛异常）。
        Long streamUserId = UserIdHolder.get();
        // 同一次运行内已获授权的调用签名（工具名 + 参数）。模型常把同一个调用重复发出，
        // 每次都弹窗会让用户觉得「批了也没用」；记忆范围刻意只限本次运行——
        // 下一次提问必须重新授权，避免一次同意变成长期放行。
        java.util.Set<String> approvedCallSignatures = java.util.concurrent.ConcurrentHashMap.newKeySet();
        registerEmitterLifecycle(emitter, streamState);
        List<ChatMessage> history = contextWindowManager.trimHistory(
                memoryService.getRecentMessages(request.getSessionId(), historyLimit), 2);

        ScheduledFuture<?> heartbeatFuture = sseHeartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        if (streamState.isDisconnected()) {
                            cancelSignal.set(true);
                            return;
                        }
                        sendEvent(emitter, "ping", Map.of("ts", System.currentTimeMillis()));
                    } catch (Exception ex) {
                        // scheduleAtFixedRate 的任务抛未捕获异常会静默终止后续所有调度；
                        // 心跳失败必须记录并吞掉，保证心跳循环不被一次瞬时故障杀死
                        log.warn("SSE heartbeat task failed", ex);
                    }
                },
                10,
                10,
                TimeUnit.SECONDS
        );

        try {

            IntentDecision streamDecision = intentClassifier.classify(request);
            AgentRuntimeListener runtimeListener = new AgentRuntimeListener() {
                @Override
                public void onRunCreated(com.nailinai.ragent.agent.dto.AgentRun run) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    sendEvent(emitter, "run_status", Map.of(
                            "runId", run.getRunId(),
                            "status", run.getStatus()
                    ));
                }

                @Override
                public void onStepStarted(com.nailinai.ragent.agent.dto.AgentStep step) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    sendEvent(emitter, "agent_step", step);
                }

                @Override
                public void onPlanUpdated(String runId,
                                          java.util.List<com.nailinai.ragent.agent.dto.AgentPlanItem> plan,
                                          String currentActionKey,
                                          java.util.List<String> completedTaskKeys,
                                          java.util.List<java.util.Map<String, Object>> skippedTaskKeys,
                                          java.util.List<String> unresolvedTaskKeys) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    // skipped / unresolved 让前端区分「合理跳过」与「漏做」；
                    // 只传事实，展示策略（是否呈现计划状态）由前端决定。
                    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("runId", runId);
                    payload.put("tasks", plan);
                    payload.put("currentActionKey", currentActionKey == null ? "" : currentActionKey);
                    payload.put("completedTaskKeys", completedTaskKeys == null ? java.util.List.of() : completedTaskKeys);
                    payload.put("skippedTaskKeys", skippedTaskKeys == null ? java.util.List.of() : skippedTaskKeys);
                    payload.put("unresolvedTaskKeys", unresolvedTaskKeys == null ? java.util.List.of() : unresolvedTaskKeys);
                    sendEvent(emitter, "agent_plan", payload);
                }

                @Override
                public void onStepCompleted(com.nailinai.ragent.agent.dto.AgentStep step) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    sendEvent(emitter, "agent_step", step);
                }

                @Override
                public void onToolResult(ToolCallTraceResponse trace) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    sendEvent(emitter, "tool_result", trace);
                }

                @Override
                public void onRunCompleted(com.nailinai.ragent.agent.dto.AgentRun run) {
                    if (streamState.isDisconnected()) {
                        return;
                    }
                    sendEvent(emitter, "run_status", Map.of(
                            "runId", run.getRunId(),
                            "status", run.getStatus()
                    ));
                }

                @Override
                public boolean supportsApproval() {
                    return approvalRegistry != null;
                }

                /**
                 * 工具审批：把待确认项推给前端，然后<b>阻塞当前线程</b>等人从另一个 HTTP 请求回话。
                 *
                 * <p>这里必须阻塞而不是「先拒绝、稍后重试」——策略要的是明确结论，
                 * 而结论只能来自人。SSE 单向，所以客户端无法在同一条连接上答复，
                 * 只能走独立的提交接口；两边通过 {@link ApprovalRegistry} 会合。</p>
                 */
                @Override
                public ApprovalOutcome requestApproval(com.nailinai.ragent.agent.dto.ApprovalRequest request) {
                    if (approvalRegistry == null || streamState.isDisconnected()) {
                        return ApprovalOutcome.UNAVAILABLE;
                    }
                    String signature = request.toolName() + "|" + request.arguments();
                    if (approvedCallSignatures.contains(signature)) {
                        // 同一运行内同一个调用已获授权，不再重复打扰用户
                        return ApprovalOutcome.APPROVED;
                    }
                    ApprovalRegistry.PendingApproval approval =
                            approvalRegistry.register(request.runId(), request.toolName(), streamUserId);
                    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("approvalId", approval.approvalId());
                    payload.put("runId", request.runId());
                    payload.put("toolName", request.toolName());
                    payload.put("displayName", request.displayName());
                    payload.put("source", request.source());
                    payload.put("arguments", request.arguments());
                    payload.put("riskSummary", request.riskSummary());
                    payload.put("timeoutSeconds", toolApprovalTimeoutMs / 1000);
                    try {
                        sendEvent(emitter, "approval_required", payload);
                    } catch (RuntimeException ex) {
                        approvalRegistry.remove(approval.approvalId());
                        return ApprovalOutcome.UNAVAILABLE;
                    }
                    try {
                        ApprovalOutcome outcome = approvalRegistry.await(approval.approvalId(),
                                toolApprovalTimeoutMs, () -> !streamState.isDisconnected());
                        if (outcome == ApprovalOutcome.APPROVED) {
                            approvedCallSignatures.add(signature);
                        }
                        return outcome;
                    } finally {
                        approvalRegistry.remove(approval.approvalId());
                    }
                }
            };
            IntentRouter.RoutingOutcome streamOutcome = intentRouter.route(streamDecision, request, history, runtimeListener, cancelSignal);

            if (streamOutcome.mode() == IntentRouter.Mode.CLARIFY) {
                streamClarify(request, emitter, streamState, streamOutcome.clarification());
                return;
            }
            if (streamOutcome.mode() == IntentRouter.Mode.DIRECT) {
                streamDirectChat(request, emitter, streamState, history, answerBuilder);
                return;
            }

            orchestration = streamOutcome.orchestration();
            ensureStreamConnected(streamState, "sse client disconnected before answer generation");

            ScheduledExecutorService chunkScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("chunk-sched");
            return t;
        });
            ChunkOptimizer messageOptimizer = new ChunkOptimizer(
                    optimizedChunk -> {
                        ensureStreamConnected(streamState, "sse client disconnected while sending message");
                        if (!sendEvent(emitter, "message", Map.of("delta", optimizedChunk))) {
                            streamState.markDisconnected();
                            throw new IllegalStateException("sse client disconnected while sending message");
                        }
                    },
                    chunkScheduler,
                    chunkSplitEnabled
            );

            try {
                finalAnswerComposer.streamAnswer(
                        orchestration.getFinalPrompt(),
                        reasoning -> {
                            if (StringUtils.hasText(reasoning)) {
                                ensureStreamConnected(streamState, "sse client disconnected while sending reasoning");
                                if (!sendEvent(emitter, "thinking", Map.of("delta", reasoning))) {
                                    streamState.markDisconnected();
                                    throw new IllegalStateException("sse client disconnected while sending reasoning");
                                }
                            }
                        },
                        chunk -> {
                            if (StringUtils.hasText(chunk)) {
                                answerBuilder.append(chunk);
                                messageOptimizer.accept(chunk);
                            }
                        }
                );
                messageOptimizer.flush();
            } finally {
                chunkScheduler.shutdownNow();
            }

            // 引用真实性校验：流式 delta 已实时发出、无法回撤，这里修正的是
            // 持久化的消息与 done 事件携带的最终回答（前端以最终回答渲染正文）
            String answer = citationValidator.validateAndFix(
                    answerBuilder.toString().trim(), orchestration.getReferences());
            if (!StringUtils.hasText(answer)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "model returned empty content");
            }
            agentRunStore.completeRun(orchestration.getRunId(), orchestration.getRunStatus(), answer,
                    orchestration.getUsage());
            sendEvent(emitter, "run_status", Map.of(
                    "runId", orchestration.getRunId(),
                    "status", orchestration.getRunStatus()
            ));

            memoryService.saveAssistantMessage(
                    request.getSessionId(),
                    request.getKbId(),
                    orchestration.getRunId(),
                    answer,
                    JsonUtils.toJson(orchestration.getReferences()),
                    JsonUtils.toJson(sanitizeToolCalls(orchestration.getToolCalls()))
            );

            List<ToolCallTraceResponse> sanitizedToolCalls = sanitizeToolCalls(orchestration.getToolCalls());
            if (!sanitizedToolCalls.isEmpty() && !sendEvent(emitter, "tool_calls", sanitizedToolCalls)) {
                emitter.complete();
                return;
            }
            if (!sendEvent(emitter, "references", orchestration.getReferences())) {
                emitter.complete();
                return;
            }
            sendEvent(emitter, "done", ChatResponse.builder()
                    .runId(orchestration.getRunId())
                    .runStatus(orchestration.getRunStatus())
                    .answer(answer)
                    .answerMode(orchestration.getAnswerMode())
                    .retrievalConfig(buildRetrievalConfig(request, orchestration.getRetrievalResult()))
                    .references(orchestration.getReferences())
                    .toolCalls(sanitizedToolCalls)
                    .agentPlan(orchestration.getAgentPlan())
                    .currentActionKey(orchestration.getCurrentActionKey())
                    .completedTaskKeys(orchestration.getCompletedTaskKeys())
                    .agentSteps(orchestration.getAgentSteps())
                    .usage(orchestration.getUsage())
                    .build());
            emitter.complete();
        } catch (Exception ex) {
            try {
                handleStreamingFailure(request, emitter, orchestration, answerBuilder.toString().trim(), ex);
                if (!isClientDisconnect(ex)) {
                    sendEvent(emitter, "error", Map.of(
                            "message", ex.getMessage() == null ? "stream chat failed" : ex.getMessage()
                    ));
                }
            } catch (Exception failureHandlingException) {
                // 兜底：失败处理自身（completeRun/落库/发事件）再出问题也不能跳过 complete，
                // 否则 SSE 连接与相关资源永久挂住。
                log.warn("stream failure handling failed, sessionId={}", request.getSessionId(), failureHandlingException);
            }
            emitter.complete();
        } finally {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
            }
        }
    }

    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        List<ChatMessage> messages = memoryService.getSessionMessages(sessionId);
        assertSessionOwner(messages);
        return messages;
    }

    @Override
    public List<SessionSummaryResponse> listSessions() {
        Long userId = com.nailinai.ragent.user.context.UserContext.currentUserId();
        List<Map<String, Object>> rows = chatMessageMapper.selectSessionSummaries(userId);
        List<SessionSummaryResponse> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String sessionId = (String) row.get("sessionId");
            String title = (String) row.get("title");
            if (title != null && title.length() > 22) {
                title = title.substring(0, 22);
            }
            Number messageCount = (Number) row.get("messageCount");
            Object kbIdObj = row.get("kbId");
            Long kbId = kbIdObj instanceof Number ? ((Number) kbIdObj).longValue() : null;
            String kbName = "";
            if (kbId != null) {
                KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
                if (kb != null) {
                    kbName = kb.getName();
                }
            }
            // MyBatis 的 Map 结果里 timestamp 列是 java.sql.Timestamp，
            // 直接强转 LocalDateTime 会 ClassCastException（接口 500 → 前端降级到旧缓存）
            Object lastActivityObj = row.get("lastActivityAt");
            String lastActivityStr = "";
            if (lastActivityObj instanceof java.time.LocalDateTime ldt) {
                lastActivityStr = ldt.toString();
            } else if (lastActivityObj instanceof java.sql.Timestamp ts) {
                lastActivityStr = ts.toLocalDateTime().toString();
            }
            result.add(SessionSummaryResponse.builder()
                    .sessionId(sessionId)
                    .title(title != null ? title : "未命名会话")
                    .preview("")
                    .kbId(kbId)
                    .kbName(kbName)
                    .messageCount(messageCount != null ? messageCount.intValue() : 0)
                    .lastActivityAt(lastActivityStr)
                    .build());
        }
        return result;
    }

    @Override
    public void deleteSession(String sessionId) {
        assertSessionOwner(memoryService.getSessionMessages(sessionId));
        chatMessageMapper.deleteBySessionId(sessionId);
    }

    /**
     * 会话归属校验：sessionId 虽是前端生成的随机串，但不能假设它不可泄露——
     * 任何登录用户拿到他人 sessionId 都不得读取或删除。
     * 空会话（尚无消息）无数据可泄露，直接放行。
     */
    private void assertSessionOwner(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        Long ownerUserId = messages.get(0).getOwnerUserId();
        Long currentUserId = com.nailinai.ragent.user.context.UserContext.currentUserId();
        // 安全：owner 为 NULL 说明历史异步路径丢过用户上下文，这类会话对任何
        // 用户（包括管理员）都不应可见可删，统一按不存在处理，避免成为共享数据。
        if (ownerUserId == null || !ownerUserId.equals(currentUserId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "session not found");
        }
    }

    @Override
    public AgentRunDetailResponse getRunDetail(String runId) {
        AgentRunDetailResponse detail = agentRunStore.getRunDetail(runId);
        // 安全：run 详情含原始提问、最终回答与全部执行步骤，必须校验归属。
        // 优先用 run 自带的 owner_user_id（建 run 时从 UserIdHolder 写入）；
        // 迁移前的历史 run 该列为 NULL，回退到旧的会话消息归属校验——
        // 两类路径都保证「非本人不可读」，不因迁移引入放行口子。
        Long ownerUserId = detail.getOwnerUserId();
        if (ownerUserId != null) {
            Long currentUserId = com.nailinai.ragent.user.context.UserContext.currentUserId();
            if (!ownerUserId.equals(currentUserId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "agent run not found");
            }
            return detail;
        }
        assertSessionOwner(memoryService.getSessionMessages(detail.getSessionId()));
        return detail;
    }

    private Map<String, Object> buildRetrievalConfig(ChatRequest request, RetrievalResult retrievalResult) {
        // LinkedHashMap 而非 Map.of：键数已到 10 个（Map.of 的上限），后续加键不再受限
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("topK", request.getTopK() == null ? topK : Math.max(1, Math.min(request.getTopK(), 20)));
        config.put("scoreThreshold", request.getScoreThreshold() == null
                ? Double.valueOf(String.format(Locale.ROOT, "%.3f", defaultScoreThreshold))
                : Double.valueOf(String.format(Locale.ROOT, "%.3f", Math.max(0.0, Math.min(request.getScoreThreshold(), 1.0)))));
        config.put("documentIds", request.getDocumentIds() == null ? List.of() : request.getDocumentIds());
        config.put("fileTypes", request.getFileTypes() == null ? List.of() : request.getFileTypes());
        config.put("documentNameKeyword", request.getDocumentNameKeyword() == null ? "" : request.getDocumentNameKeyword());
        config.put("originalQuery", retrievalResult.getOriginalQuery());
        config.put("effectiveQuery", retrievalResult.getEffectiveQuery());
        config.put("queryRewritten", retrievalResult.isQueryRewritten());
        config.put("reranked", retrievalResult.isReranked());
        // 通道可见性：哪个通道因异常退出了本轮检索（空列表 = 全部正常）。
        // 通道静默降级会让混合检索无声变成单通道检索，必须让调用方看得到。
        config.put("degradedChannels", retrievalResult.getDegradedChannels() == null
                ? List.of()
                : retrievalResult.getDegradedChannels());
        return config;
    }

    private void streamClarify(ChatRequest request,
                               SseEmitter emitter,
                               StreamState streamState,
                               String clarification) {
        if (!StringUtils.hasText(clarification)) {
            clarification = "请问您能进一步说明一下需求吗？";
        }

        ensureStreamConnected(streamState, "sse client disconnected before clarify");
        if (!sendEvent(emitter, "clarify", Map.of("clarification", clarification))) {
            streamState.markDisconnected();
            emitter.complete();
            return;
        }

        memoryService.saveAssistantMessage(
                request.getSessionId(),
                request.getKbId(),
                null,
                clarification,
                JsonUtils.toJson(List.of()),
                JsonUtils.toJson(List.of())
        );

        sendEvent(emitter, "done", ChatResponse.builder()
                .answer(clarification)
                .answerMode("clarify")
                .clarification(clarification)
                .references(List.of())
                .toolCalls(List.of())
                .agentPlan(List.of())
                .completedTaskKeys(List.of())
                .agentSteps(List.of())
                .build());
        emitter.complete();
    }

    private String directChat(List<ChatMessage> history, String question, List<String> skillNames) {
        String prompt = promptBuilder.buildPrompt(
                question,
                history,
                List.of(),
                null,
                null,
                null,
                false
        );
        String answer = chatClient.chat(prompt);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "model returned empty content");
        }
        return answer;
    }

    private void streamDirectChat(ChatRequest request,
                                  SseEmitter emitter,
                                  StreamState streamState,
                                  List<ChatMessage> history,
                                  StringBuilder answerBuilder) {
        String prompt = promptBuilder.buildPrompt(
                request.getQuestion(),
                history,
                List.of(),
                null,
                null,
                null,
                false
        );

        ScheduledExecutorService chunkScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("chunk-sched");
            return t;
        });
        ChunkOptimizer messageOptimizer = new ChunkOptimizer(
                optimizedChunk -> {
                    ensureStreamConnected(streamState, "sse client disconnected while sending message");
                    if (!sendEvent(emitter, "message", Map.of("delta", optimizedChunk))) {
                        streamState.markDisconnected();
                        throw new IllegalStateException("sse client disconnected while sending message");
                    }
                },
                chunkScheduler,
                chunkSplitEnabled
        );

        try {
            chatClient.streamChat(
                    prompt,
                    reasoning -> {
                        if (StringUtils.hasText(reasoning)) {
                            ensureStreamConnected(streamState, "sse client disconnected while sending reasoning");
                            if (!sendEvent(emitter, "thinking", Map.of("delta", reasoning))) {
                                streamState.markDisconnected();
                                throw new IllegalStateException("sse client disconnected while sending reasoning");
                            }
                        }
                    },
                    chunk -> {
                        if (StringUtils.hasText(chunk)) {
                            answerBuilder.append(chunk);
                            messageOptimizer.accept(chunk);
                        }
                    }
            );
            messageOptimizer.flush();
        } finally {
            chunkScheduler.shutdownNow();
        }

        String answer = answerBuilder.toString().trim();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "model returned empty content");
        }

        memoryService.saveAssistantMessage(
                request.getSessionId(),
                request.getKbId(),
                null,
                answer,
                JsonUtils.toJson(List.of()),
                JsonUtils.toJson(List.of())
        );

        sendEvent(emitter, "done", ChatResponse.builder()
                .answer(answer)
                .answerMode("chat")
                .references(List.of())
                .toolCalls(List.of())
                .agentPlan(List.of())
                .completedTaskKeys(List.of())
                .agentSteps(List.of())
                .build());
        emitter.complete();
    }

    private boolean sendEvent(SseEmitter emitter, String eventName, Object payload) {
        // 心跳线程与业务线程会并发调用本方法，而 SseEmitter.send 不是线程安全的，
        // 并发 send 会导致 SSE 帧交叉损坏。以 emitter 实例本身作为每条流的专用
        // send 锁（一个 emitter 对应一条流），所有事件串行写出。
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(JsonUtils.toJson(sanitizeSsePayload(payload))));
                return true;
            } catch (IOException | IllegalStateException ex) {
                return false;
            }
        }
    }

    @Override
    public boolean decideApproval(String approvalId, boolean approved) {
        if (approvalRegistry == null || !StringUtils.hasText(approvalId)) {
            return false;
        }
        // 归属校验在注册表内完成：决定只能由发起该次运行的用户提交
        return approvalRegistry.decide(approvalId, approved, UserIdHolder.get());
    }

    private void registerEmitterLifecycle(SseEmitter emitter, StreamState streamState) {        emitter.onCompletion(streamState::markDisconnected);
        emitter.onTimeout(streamState::markDisconnected);
        emitter.onError(ex -> streamState.markDisconnected());
    }

    private void ensureStreamConnected(StreamState streamState, String message) {
        if (streamState.isDisconnected()) {
            throw new IllegalStateException(message);
        }
    }

    private boolean isClientDisconnect(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("broken pipe")
                || normalized.contains("connection reset")
                || normalized.contains("client disconnected")
                || normalized.contains("failed to send sse event")
                || normalized.contains("sse client disconnected");
    }

    private Object sanitizeSsePayload(Object payload) {
        if (payload instanceof ToolCallTraceResponse traceResponse) {
            return sanitizeToolCall(traceResponse);
        }
        if (payload instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof ToolCallTraceResponse) {
            @SuppressWarnings("unchecked")
            List<ToolCallTraceResponse> toolCalls = (List<ToolCallTraceResponse>) list;
            return sanitizeToolCalls(toolCalls);
        }
        if (payload instanceof ChatResponse response) {
            response.setToolCalls(sanitizeToolCalls(response.getToolCalls()));
            return response;
        }
        return payload;
    }

    private List<ToolCallTraceResponse> sanitizeToolCalls(List<ToolCallTraceResponse> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCallTraceResponse> sanitized = new ArrayList<>(toolCalls.size());
        for (ToolCallTraceResponse toolCall : toolCalls) {
            sanitized.add(sanitizeToolCall(toolCall));
        }
        return List.copyOf(sanitized);
    }

    private ToolCallTraceResponse sanitizeToolCall(ToolCallTraceResponse toolCall) {
        if (toolCall == null) {
            return null;
        }
        return ToolCallTraceResponse.builder()
                .toolName(toolCall.getToolName())
                .displayName(toolCall.getDisplayName())
                .source(toolCall.getSource())
                .status(toolCall.getStatus())
                .summary(toolCall.getSummary())
                .resultPreview(toolCall.getResultPreview())
                .rawResult(null)
                .durationMs(toolCall.getDurationMs())
                .build();
    }

    private void handleStreamingFailure(ChatRequest request,
                                        SseEmitter emitter,
                                        ChatAgentOrchestrator.ToolOrchestrationResult orchestration,
                                        String partialAnswer,
                                        Exception exception) {
        if (orchestration == null) {
            return;
        }

        try {
            boolean hasPartialAnswer = StringUtils.hasText(partialAnswer);
            String fallbackStatus = hasPartialAnswer ? "PARTIAL" : "FAILED";
            agentRunStore.completeRun(orchestration.getRunId(), fallbackStatus, hasPartialAnswer ? partialAnswer : null);

            if (hasPartialAnswer && !isClientDisconnect(exception)) {
                memoryService.saveAssistantMessage(
                        request.getSessionId(),
                        request.getKbId(),
                        orchestration.getRunId(),
                        partialAnswer,
                        JsonUtils.toJson(orchestration.getReferences()),
                        JsonUtils.toJson(sanitizeToolCalls(orchestration.getToolCalls()))
                );
            }

            if (!isClientDisconnect(exception)) {
                sendEvent(emitter, "run_status", Map.of(
                        "runId", orchestration.getRunId(),
                        "status", fallbackStatus
                ));
            }
        } catch (Exception cleanupException) {
            // 调用方已经在外层兜底 complete，但这里如果把 DB 故障等再抛上去，
            // 会覆盖掉原始异常上下文并可能跳过后续 sendEvent。记录后吞掉。
            log.warn("failed to persist partial result for runId={}", orchestration.getRunId(), cleanupException);
        }
    }


    private static final class StreamState {
        private final AtomicBoolean disconnected = new AtomicBoolean(false);

        boolean isDisconnected() {
            return disconnected.get();
        }

        void markDisconnected() {
            disconnected.set(true);
        }
    }
}
