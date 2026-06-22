package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.ObservationBuilder;
import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.chat.service.DocumentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KnowledgeBaseCatalogToolExecutor implements ToolExecutor {

    private final DocumentService documentService;
    private final ObservationBuilder observationBuilder;

    public KnowledgeBaseCatalogToolExecutor(DocumentService documentService,
                                            ObservationBuilder observationBuilder) {
        this.documentService = documentService;
        this.observationBuilder = observationBuilder;
    }

    @Override
    public String getToolName() {
        return "kb_catalog";
    }

    @Override
    public String getDisplayName() {
        return "KB Catalog";
    }

    @Override
    public String getDescription() {
        return "List documents in the current knowledge base, including ids, file types, status, and chunk counts.";
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
        long startTime = System.currentTimeMillis();
        if (context.getRequest().getKbId() == null) {
            String summary = "No knowledge base selected";
            String observation = "No knowledge base is selected for this conversation, so kb_catalog cannot list any documents.";
            return ToolExecutionResult.builder()
                    .trace(ToolCallTraceResponse.builder()
                            .toolName(getToolName())
                            .displayName(getDisplayName())
                            .source(getSource())
                            .status("FAILED")
                            .arguments(Map.of())
                            .summary(summary)
                            .resultPreview(observation)
                            .durationMs(System.currentTimeMillis() - startTime)
                            .build())
                    .summary(summary)
                    .supplementalContext(observation)
                    .references(List.of())
                    .rawResult(Map.of())
                    .observation(observation)
                    .build();
        }
        List<DocumentResponse> documents = documentService.listByKbId(context.getRequest().getKbId());
        String observation = observationBuilder.buildCatalogObservation(documents);
        String resultPreview = documents.isEmpty()
                ? "No documents found in the current knowledge base."
                : documents.stream()
                .limit(20)
                .map(document -> "#%d %s | type=%s | status=%s | chunks=%s".formatted(
                        document.getId(),
                        document.getName(),
                        document.getFileType(),
                        document.getStatus(),
                        document.getChunkCount()
                ))
                .collect(Collectors.joining("\n"));

        Map<String, Object> rawResult = new LinkedHashMap<>();
        rawResult.put("documentCount", documents.size());
        rawResult.put("documents", documents);

        return ToolExecutionResult.builder()
                .trace(ToolCallTraceResponse.builder()
                        .toolName(getToolName())
                        .displayName(getDisplayName())
                        .source(getSource())
                        .status("SUCCESS")
                        .arguments(Map.of())
                        .summary(documents.isEmpty() ? "Knowledge base is empty" : "Listed %d documents".formatted(documents.size()))
                        .resultPreview(resultPreview)
                        .rawResult(rawResult)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build())
                .summary(documents.isEmpty() ? "Knowledge base is empty" : "Listed %d documents".formatted(documents.size()))
                .supplementalContext(observation)
                .references(List.of())
                .rawResult(rawResult)
                .observation(observation)
                .build();
    }
}
