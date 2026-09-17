package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天链路上的 Agent 编排器。
 *
 * <p>职责：检索 → 交给 {@link AgentRuntimeService} 跑多步循环 → 用 {@link FinalAnswerComposer}
 * 组装最终回答提示词。
 *
 * <p>历史说明：本类曾包含一条「单轮工具规划」链路（{@code decideToolPlan} 及其年份正则归一逻辑），
 * 那是 v2.1 之前的遗留实现，在引入多步 Agent Runtime 后已无调用方，于本次改造中删除。
 */
@Component
public class ChatAgentOrchestrator {

    private final RetrievalService retrievalService;
    private final AgentRuntimeService agentRuntimeService;
    private final FinalAnswerComposer finalAnswerComposer;
    private final int defaultTopK;
    private final double defaultScoreThreshold;

    public ChatAgentOrchestrator(RetrievalService retrievalService,
                                 AgentRuntimeService agentRuntimeService,
                                 FinalAnswerComposer finalAnswerComposer,
                                 @Value("${app.rag.top-k}") int defaultTopK,
                                 @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold) {
        this.retrievalService = retrievalService;
        this.agentRuntimeService = agentRuntimeService;
        this.finalAnswerComposer = finalAnswerComposer;
        this.defaultTopK = defaultTopK;
        this.defaultScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
    }

    public RetrievalResult retrieve(ChatRequest request) {
        return retrievalService.retrieve(request, defaultTopK, defaultScoreThreshold);
    }

    public ToolOrchestrationResult prepare(ChatRequest request, List<ChatMessage> history, RetrievalResult retrievalResult) {
        return prepare(request, history, retrievalResult, AgentRuntimeListener.NOOP, null);
    }

    public ToolOrchestrationResult prepare(ChatRequest request,
                                           List<ChatMessage> history,
                                           RetrievalResult retrievalResult,
                                           AgentRuntimeListener listener) {
        return prepare(request, history, retrievalResult, listener, null);
    }

    public ToolOrchestrationResult prepare(ChatRequest request,
                                           List<ChatMessage> history,
                                           RetrievalResult retrievalResult,
                                           AgentRuntimeListener listener,
                                           AtomicBoolean cancelSignal) {
        AgentRuntimeResult runtimeResult = agentRuntimeService.run(request, history, retrievalResult, listener, cancelSignal);
        String finalPrompt = finalAnswerComposer.buildPrompt(request, history, runtimeResult);
        return ToolOrchestrationResult.builder()
                .runId(runtimeResult.getRun().getRunId())
                .runStatus(runtimeResult.getRun().getStatus())
                .finalPrompt(finalPrompt)
                .agentPlan(runtimeResult.getPlan())
                .currentActionKey(runtimeResult.getCurrentActionKey())
                .completedTaskKeys(runtimeResult.getCompletedTaskKeys())
                .toolCalls(runtimeResult.getToolCalls())
                .references(runtimeResult.getReferences())
                .retrievalResult(runtimeResult.getRetrievalResult())
                .answerMode(runtimeResult.getAnswerMode())
                .agentSteps(runtimeResult.getSteps())
                .usage(runtimeResult.getUsage())
                .build();
    }

    @lombok.Builder
    @lombok.Value
    public static class ToolOrchestrationResult {
        String runId;
        String runStatus;
        String finalPrompt;
        List<AgentPlanItem> agentPlan;
        String currentActionKey;
        List<String> completedTaskKeys;
        List<ToolCallTraceResponse> toolCalls;
        List<ReferenceChunkResponse> references;
        RetrievalResult retrievalResult;
        String answerMode;
        List<AgentStep> agentSteps;
        com.nailinai.ragent.agent.dto.RunUsage usage;
    }
}
