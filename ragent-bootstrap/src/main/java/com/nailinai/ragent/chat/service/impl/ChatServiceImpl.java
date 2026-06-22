package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.agent.ChatAgentOrchestrator;
import com.nailinai.ragent.agent.FinalAnswerComposer;
import com.nailinai.ragent.agent.AgentRunStore;
import com.nailinai.ragent.agent.AgentRuntimeListener;
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
import com.nailinai.ragent.util.PromptBuilder;
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
import java.util.concurrent.TimeUnit;

@Service
public class ChatServiceImpl implements ChatService {

    private final MemoryService memoryService;
    private final ChatAgentOrchestrator chatAgentOrchestrator;
    private final FinalAnswerComposer finalAnswerComposer;
    private final AgentRunStore agentRunStore;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final int topK;
    private final int historyLimit;
    private final double defaultScoreThreshold;

    public ChatServiceImpl(MemoryService memoryService,
                           ChatAgentOrchestrator chatAgentOrchestrator,
                           FinalAnswerComposer finalAnswerComposer,
                           AgentRunStore agentRunStore,
                           PromptBuilder promptBuilder,
                           ChatClient chatClient,
                           ChatMessageMapper chatMessageMapper,
                           KnowledgeBaseMapper knowledgeBaseMapper,
                           @Value("${app.rag.top-k}") int topK,
                           @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold,
                           @Value("${app.rag.history-limit}") int historyLimit) {
        this.memoryService = memoryService;
        this.chatAgentOrchestrator = chatAgentOrchestrator;
        this.finalAnswerComposer = finalAnswerComposer;
        this.agentRunStore = agentRunStore;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.chatMessageMapper = chatMessageMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.topK = topK;
        this.historyLimit = historyLimit;
        this.defaultScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        memoryService.saveUserMessage(request.getSessionId(), request.getKbId(), request.getQuestion());
        List<ChatMessage> history = memoryService.getRecentMessages(request.getSessionId(), historyLimit);
        if (shouldUseDirectChat(request)) {
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
        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = chatAgentOrchestrator.prepare(
                request,
                history,
                chatAgentOrchestrator.retrieve(request)
        );
        try {
            String answer = finalAnswerComposer.composeAnswer(orchestration.getFinalPrompt());
            agentRunStore.completeRun(orchestration.getRunId(), orchestration.getRunStatus(), answer);

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
        memoryService.saveUserMessage(request.getSessionId(), request.getKbId(), request.getQuestion());
        ScheduledExecutorService heartbeatExecutor = null;
        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = null;
        StringBuilder answerBuilder = new StringBuilder();
        StreamState streamState = new StreamState();
        registerEmitterLifecycle(emitter, streamState);
        List<ChatMessage> history = memoryService.getRecentMessages(request.getSessionId(), historyLimit);

        try {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
            SseEmitter finalEmitter = emitter;
            heartbeatExecutor.scheduleAtFixedRate(
                    () -> {
                        if (streamState.isDisconnected()) {
                            return;
                        }
                        sendEvent(finalEmitter, "ping", Map.of("ts", System.currentTimeMillis()));
                    },
                    10,
                    10,
                    TimeUnit.SECONDS
            );

            if (shouldUseDirectChat(request)) {
                streamDirectChat(request, emitter, streamState, history, answerBuilder);
                return;
            }

            orchestration = chatAgentOrchestrator.prepare(
                    request,
                    history,
                    chatAgentOrchestrator.retrieve(request),
                    new AgentRuntimeListener() {
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
                        public void onPlanUpdated(String runId, java.util.List<com.nailinai.ragent.agent.dto.AgentPlanItem> plan, String currentActionKey, java.util.List<String> completedTaskKeys) {
                            if (streamState.isDisconnected()) {
                                return;
                            }
                            sendEvent(emitter, "agent_plan", Map.of(
                                    "runId", runId,
                                    "tasks", plan,
                                    "currentActionKey", currentActionKey == null ? "" : currentActionKey,
                                    "completedTaskKeys", completedTaskKeys == null ? java.util.List.of() : completedTaskKeys
                            ));
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
                    }
            );
            ensureStreamConnected(streamState, "sse client disconnected before answer generation");

            ScheduledExecutorService chunkScheduler = Executors.newSingleThreadScheduledExecutor();
            ChunkOptimizer messageOptimizer = new ChunkOptimizer(
                    optimizedChunk -> {
                        ensureStreamConnected(streamState, "sse client disconnected while sending message");
                        if (!sendEvent(emitter, "message", Map.of("delta", optimizedChunk))) {
                            streamState.markDisconnected();
                            throw new IllegalStateException("sse client disconnected while sending message");
                        }
                    },
                    chunkScheduler
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

            String answer = answerBuilder.toString().trim();
            if (!StringUtils.hasText(answer)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "model returned empty content");
            }
            agentRunStore.completeRun(orchestration.getRunId(), orchestration.getRunStatus(), answer);
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
                    .build());
            emitter.complete();
        } catch (Exception ex) {
            handleStreamingFailure(request, emitter, orchestration, answerBuilder.toString().trim(), ex);
            if (!isClientDisconnect(ex)) {
                sendEvent(emitter, "error", Map.of(
                        "message", ex.getMessage() == null ? "stream chat failed" : ex.getMessage()
                ));
            }
            emitter.complete();
        } finally {
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
            }
        }
    }

    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return memoryService.getSessionMessages(sessionId);
    }

    @Override
    public List<SessionSummaryResponse> listSessions() {
        List<Map<String, Object>> rows = chatMessageMapper.selectSessionSummaries();
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
            java.time.LocalDateTime lastActivityAt = (java.time.LocalDateTime) row.get("lastActivityAt");
            String lastActivityStr = lastActivityAt != null ? lastActivityAt.toString() : "";
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
        chatMessageMapper.deleteBySessionId(sessionId);
    }

    @Override
    public AgentRunDetailResponse getRunDetail(String runId) {
        return agentRunStore.getRunDetail(runId);
    }

    private Map<String, Object> buildRetrievalConfig(ChatRequest request, RetrievalResult retrievalResult) {
        return Map.of(
                "topK", request.getTopK() == null ? topK : Math.max(1, Math.min(request.getTopK(), 20)),
                "scoreThreshold", request.getScoreThreshold() == null
                        ? Double.valueOf(String.format(Locale.ROOT, "%.3f", defaultScoreThreshold))
                        : Double.valueOf(String.format(Locale.ROOT, "%.3f", Math.max(0.0, Math.min(request.getScoreThreshold(), 1.0)))),
                "documentIds", request.getDocumentIds() == null ? List.of() : request.getDocumentIds(),
                "fileTypes", request.getFileTypes() == null ? List.of() : request.getFileTypes(),
                "documentNameKeyword", request.getDocumentNameKeyword() == null ? "" : request.getDocumentNameKeyword(),
                "originalQuery", retrievalResult.getOriginalQuery(),
                "effectiveQuery", retrievalResult.getEffectiveQuery(),
                "queryRewritten", retrievalResult.isQueryRewritten(),
                "reranked", retrievalResult.isReranked()
        );
    }

    private boolean shouldUseDirectChat(ChatRequest request) {
        return request.getKbId() == null
                && !Boolean.TRUE.equals(request.getAgentEnabled())
                && (request.getSkillNames() == null || request.getSkillNames().isEmpty());
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

        ScheduledExecutorService chunkScheduler = Executors.newSingleThreadScheduledExecutor();
        ChunkOptimizer messageOptimizer = new ChunkOptimizer(
                optimizedChunk -> {
                    ensureStreamConnected(streamState, "sse client disconnected while sending message");
                    if (!sendEvent(emitter, "message", Map.of("delta", optimizedChunk))) {
                        streamState.markDisconnected();
                        throw new IllegalStateException("sse client disconnected while sending message");
                    }
                },
                chunkScheduler
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
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(JsonUtils.toJson(sanitizeSsePayload(payload))));
            return true;
        } catch (IOException | IllegalStateException ex) {
            return false;
        }
    }

    private void registerEmitterLifecycle(SseEmitter emitter, StreamState streamState) {
        emitter.onCompletion(streamState::markDisconnected);
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
                .arguments(toolCall.getArguments())
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
