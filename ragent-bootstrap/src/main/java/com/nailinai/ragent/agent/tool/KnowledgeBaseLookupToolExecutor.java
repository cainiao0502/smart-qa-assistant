package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.chat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class KnowledgeBaseLookupToolExecutor implements ToolExecutor {

    private final RetrievalService retrievalService;
    private final int defaultTopK;
    private final double defaultScoreThreshold;

    public KnowledgeBaseLookupToolExecutor(RetrievalService retrievalService,
                                           @Value("${app.rag.top-k}") int defaultTopK,
                                           @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold) {
        this.retrievalService = retrievalService;
        this.defaultTopK = defaultTopK;
        this.defaultScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
    }

    @Override
    public String getToolName() {
        return "kb_lookup";
    }

    @Override
    public String getDisplayName() {
        return "KB Lookup";
    }

    @Override
    public String getDescription() {
        return "在当前知识库中做聚焦检索，返回更精确的文档片段。当已有检索上下文过于宽泛、或不足以回答问题时使用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "检索查询语句，应具体描述要找的信息"));
        properties.put("topK", Map.of("type", "integer", "description", "返回片段数量上限（1-8）", "minimum", 1, "maximum", 8));
        properties.put("scoreThreshold", Map.of("type", "number", "description", "相似度阈值（0-1），越高越严格", "minimum", 0, "maximum", 1));
        return ToolExecutor.objectSchema(properties, List.of("query"));
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
        long startTime = System.currentTimeMillis();
        String query = valueAsString(arguments.get("query"));
        String normalizedQuery = StringUtils.hasText(query)
                ? query.trim()
                : context.getRequest().getQuestion().trim();

        ChatRequest toolRequest = new ChatRequest();
        toolRequest.setKbId(context.getRequest().getKbId());
        toolRequest.setSessionId(context.getRequest().getSessionId());
        toolRequest.setQuestion(normalizedQuery);
        toolRequest.setTopK(resolveTopK(arguments.get("topK")));
        toolRequest.setScoreThreshold(resolveScoreThreshold(arguments.get("scoreThreshold")));
        toolRequest.setDocumentIds(context.getRequest().getDocumentIds());
        toolRequest.setFileTypes(context.getRequest().getFileTypes());
        toolRequest.setDocumentNameKeyword(context.getRequest().getDocumentNameKeyword());

        RetrievalResult retrievalResult = retrievalService.retrieve(toolRequest, defaultTopK, defaultScoreThreshold);
        List<ReferenceChunkResponse> references = retrievalResult.getChunks().stream()
                .map(this::toReference)
                .toList();

        String supplementalContext = references.isEmpty()
                ? "Tool kb_lookup did not find additional focused passages."
                : references.stream()
                .map(reference -> {
                    String source = "%s#%s".formatted(
                            reference.getDocumentName() == null ? "unknown" : reference.getDocumentName(),
                            reference.getChunkIndex() == null ? "?" : reference.getChunkIndex()
                    );
                    return "[%s]%n%s".formatted(source, reference.getChunkText());
                })
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        String summary = references.isEmpty()
                ? "No additional focused passages found"
                : "Fetched %d focused passages".formatted(references.size());

        Map<String, Object> traceArguments = new LinkedHashMap<>();
        traceArguments.put("query", normalizedQuery);
        traceArguments.put("topK", toolRequest.getTopK());
        traceArguments.put("scoreThreshold", Double.valueOf(String.format(Locale.ROOT, "%.2f", toolRequest.getScoreThreshold())));

        return ToolExecutionResult.builder()
                .trace(ToolCallTraceResponse.builder()
                        .toolName(getToolName())
                        .displayName(getDisplayName())
                        .source(getSource())
                        .status("SUCCESS")
                        .arguments(traceArguments)
                        .summary(summary)
                        .resultPreview(supplementalContext)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build())
                .summary(summary)
                .supplementalContext(supplementalContext)
                .references(references)
                .rawResult(retrievalResult)
                .observation(summary)
                .build();
    }

    private Integer resolveTopK(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 8));
        }
        return Math.min(Math.max(defaultTopK, 1), 8);
    }

    private Double resolveScoreThreshold(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0, Math.min(number.doubleValue(), 1.0));
        }
        return defaultScoreThreshold;
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ReferenceChunkResponse toReference(DocumentChunk chunk) {
        return ReferenceChunkResponse.builder()
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
    }
}
