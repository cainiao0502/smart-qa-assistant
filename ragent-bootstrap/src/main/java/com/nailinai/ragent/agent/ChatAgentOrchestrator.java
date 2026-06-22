package com.nailinai.ragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.ToolCallPlan;
import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.chat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ChatAgentOrchestrator {
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b20\\d{2}\\b");

    private final RetrievalService retrievalService;
    private final ChatClient chatClient;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final ObjectMapper objectMapper;
    private final DocumentService documentService;
    private final AgentRuntimeService agentRuntimeService;
    private final FinalAnswerComposer finalAnswerComposer;
    private final int defaultTopK;
    private final double defaultScoreThreshold;

    public ChatAgentOrchestrator(RetrievalService retrievalService,
                                 ChatClient chatClient,
                                 ToolExecutorRegistry toolExecutorRegistry,
                                 ObjectMapper objectMapper,
                                 DocumentService documentService,
                                 AgentRuntimeService agentRuntimeService,
                                 FinalAnswerComposer finalAnswerComposer,
                                 @Value("${app.rag.top-k}") int defaultTopK,
                                 @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.objectMapper = objectMapper;
        this.documentService = documentService;
        this.agentRuntimeService = agentRuntimeService;
        this.finalAnswerComposer = finalAnswerComposer;
        this.defaultTopK = defaultTopK;
        this.defaultScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
    }

    public RetrievalResult retrieve(ChatRequest request) {
        return retrievalService.retrieve(request, defaultTopK, defaultScoreThreshold);
    }

    public ToolOrchestrationResult prepare(ChatRequest request, List<ChatMessage> history, RetrievalResult retrievalResult) {
        return prepare(request, history, retrievalResult, AgentRuntimeListener.NOOP);
    }

    public ToolOrchestrationResult prepare(ChatRequest request,
                                           List<ChatMessage> history,
                                           RetrievalResult retrievalResult,
                                           AgentRuntimeListener listener) {
        AgentRuntimeResult runtimeResult = agentRuntimeService.run(request, history, retrievalResult, listener);
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
                .build();
    }

    public ToolCallPlan decideToolPlan(ChatRequest request, List<ChatMessage> history, List<DocumentChunk> chunks) {
        String response = chatClient.chat(buildToolPlanningPrompt(request, history, chunks));
        if (!StringUtils.hasText(response)) {
            return null;
        }
        try {
            return objectMapper.readValue(extractJsonObject(response), ToolCallPlan.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildToolPlanningPrompt(ChatRequest request, List<ChatMessage> history, List<DocumentChunk> chunks) {
        LocalDate today = LocalDate.now();
        String historyText = history.stream()
                .skip(Math.max(0, history.size() - 4L))
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        String contextText = chunks.stream()
                .limit(4)
                .map(chunk -> {
                    String source = chunk.getDocumentName() == null ? "unknown" : chunk.getDocumentName();
                    return "[%s#%s] %s".formatted(source, chunk.getChunkIndex(), chunk.getChunkText());
                })
                .collect(Collectors.joining("\n\n"));

        return """
                You are a tool planner for a RAG assistant.
                Decide whether the assistant should call one tool before answering.

                Available tools:
                %s

                Planning rules:
                - Prefer action "none" when the retrieved knowledge already looks sufficient.
                - Use "kb_lookup" when the user wants more precise document evidence, more focused passages, or the current retrieved context seems too broad or insufficient.
                - Use an MCP tool when the user needs external system data or a capability explicitly described by that tool.
                - Choose the tool whose description best matches the user intent.
                - Today is %s. When the user asks for latest/current/recent information, generate tool arguments using this date and year instead of guessing an older year.
                - For web/search-style tools, if the user asks for "最新", "最近", "当前", "today", "latest", or "recent", avoid historical years unless the user explicitly asked for a historical year.
                - Return JSON only, no markdown, no explanation.
                - JSON schema:
                  {"action":"none"}
                  or
                  {"action":"tool_call","tool":"tool_name","arguments":{"query":"...","topK":3,"scoreThreshold":0.55}}

                Conversation history:
                %s

                User question:
                %s

                Current retrieved context:
                %s
                """.formatted(
                toolExecutorRegistry.describeTools(),
                today,
                historyText.isBlank() ? "(empty)" : historyText,
                request.getQuestion(),
                contextText.isBlank() ? "(empty)" : contextText
        );
    }

    private Map<String, Object> normalizeToolArguments(ChatRequest request, ToolCallPlan plan) {
        Map<String, Object> original = plan.getArguments() == null ? Map.of() : plan.getArguments();
        if (original.isEmpty()) {
            return original;
        }
        if (!isLatestIntent(request.getQuestion())) {
            return original;
        }

        Object queryValue = original.get("query");
        if (!(queryValue instanceof String query) || !StringUtils.hasText(query)) {
            return original;
        }

        LocalDate today = LocalDate.now();
        String userQuestion = request.getQuestion() == null ? "" : request.getQuestion();
        String normalizedQuery = query.trim();

        if (!containsExplicitYear(userQuestion)) {
            if (YEAR_PATTERN.matcher(normalizedQuery).find()) {
                normalizedQuery = YEAR_PATTERN.matcher(normalizedQuery).replaceAll(String.valueOf(today.getYear()));
            } else {
                normalizedQuery = normalizedQuery + " " + today.getYear() + " " + today.getMonthValue() + "月";
            }
        }

        if (normalizedQuery.equals(query)) {
            return original;
        }

        Map<String, Object> updated = new LinkedHashMap<>(original);
        updated.put("query", normalizedQuery);
        return updated;
    }

    private boolean isLatestIntent(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.toLowerCase();
        return normalized.contains("最新")
                || normalized.contains("最近")
                || normalized.contains("当前")
                || normalized.contains("今天")
                || normalized.contains("latest")
                || normalized.contains("recent")
                || normalized.contains("current")
                || normalized.contains("today");
    }

    private boolean containsExplicitYear(String text) {
        return StringUtils.hasText(text) && YEAR_PATTERN.matcher(text).find();
    }

    private String buildDocumentCatalog(Long kbId) {
        if (kbId == null) {
            return "";
        }
        List<DocumentResponse> documents = documentService.listByKbId(kbId);
        if (documents.isEmpty()) {
            return "当前知识库没有已上传的文档记录。";
        }
        return documents.stream()
                .limit(50)
                .map(document -> "- %s | type=%s | status=%s | chunks=%s".formatted(
                        document.getName(),
                        document.getFileType() == null ? "unknown" : document.getFileType(),
                        document.getStatus() == null ? "unknown" : document.getStatus().name(),
                        document.getChunkCount() == null ? "?" : document.getChunkCount()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private List<ReferenceChunkResponse> mergeReferences(List<DocumentChunk> baseChunks, List<ReferenceChunkResponse> extraReferences) {
        Map<String, ReferenceChunkResponse> merged = new LinkedHashMap<>();
        for (DocumentChunk chunk : baseChunks) {
            ReferenceChunkResponse reference = ReferenceChunkResponse.builder()
                    .docId(chunk.getDocId())
                    .documentName(chunk.getDocumentName())
                    .fileType(chunk.getFileType())
                    .chunkIndex(chunk.getChunkIndex())
                    .paragraphIndex(chunk.getParagraphIndex())
                    .chunkText(chunk.getChunkText())
                    .score(chunk.getScore())
                    .distance(chunk.getDistance())
                    .rerankScore(chunk.getRerankScore())
                    .hitReason(chunk.getHitReason())
                    .build();
            merged.put(referenceKey(reference), reference);
        }
        for (ReferenceChunkResponse reference : extraReferences) {
            merged.putIfAbsent(referenceKey(reference), reference);
        }
        return new ArrayList<>(merged.values());
    }

    private String referenceKey(ReferenceChunkResponse reference) {
        return "%s:%s".formatted(reference.getDocId(), reference.getChunkIndex());
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
    }
}
