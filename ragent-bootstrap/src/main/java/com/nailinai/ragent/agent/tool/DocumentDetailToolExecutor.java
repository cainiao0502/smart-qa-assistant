package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.ObservationBuilder;
import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.chat.service.DocumentService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocumentDetailToolExecutor implements ToolExecutor {

    private final DocumentService documentService;
    private final ObservationBuilder observationBuilder;

    public DocumentDetailToolExecutor(DocumentService documentService,
                                      ObservationBuilder observationBuilder) {
        this.documentService = documentService;
        this.observationBuilder = observationBuilder;
    }

    @Override
    public String getToolName() {
        return "document_detail";
    }

    @Override
    public String getDisplayName() {
        return "Document Detail";
    }

    @Override
    public String getDescription() {
        return "按 docId 或文档名深入读取单个文档，返回内容摘要与切片摘要。通常在 kb_catalog 之后使用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("docId", Map.of("type", "integer", "description", "文档 ID，来自 kb_catalog 返回的清单"));
        properties.put("documentName", Map.of("type", "string", "description", "文档名，docId 不可用时使用"));
        return ToolExecutor.objectSchema(properties, List.of());
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
        long startTime = System.currentTimeMillis();
        if (context.getRequest().getKbId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "document_detail requires a selected knowledge base");
        }
        DocumentResponse target = resolveDocument(arguments, context.getRequest().getKbId());
        DocumentDetailResponse detail = documentService.getDetail(target.getId());
        String observation = observationBuilder.buildDocumentDetailObservation(detail);

        Map<String, Object> traceArguments = new LinkedHashMap<>();
        traceArguments.put("docId", detail.getId());
        traceArguments.put("documentName", detail.getName());

        // 深读过的文档就是最终回答的主要依据，其切片必须进入引用溯源，
        // 否则前端 ReferencePanel 拿不到来源（此前固定返回 List.of() 导致 references 为空）
        List<ReferenceChunkResponse> references = detail.getChunks() == null ? List.of()
                : detail.getChunks().stream()
                        .map(chunk -> ReferenceChunkResponse.builder()
                                .docId(detail.getId())
                                .documentName(detail.getName())
                                .fileType(detail.getFileType())
                                .chunkIndex(chunk.getChunkIndex())
                                .paragraphIndex(chunk.getParagraphIndex())
                                .chunkText(chunk.getChunkText())
                                .build())
                        .toList();

        return ToolExecutionResult.builder()
                .trace(ToolCallTraceResponse.builder()
                        .toolName(getToolName())
                        .displayName(getDisplayName())
                        .source(getSource())
                        .status("SUCCESS")
                        .arguments(traceArguments)
                        .summary("Loaded document %s".formatted(detail.getName()))
                        .resultPreview(observationBuilder.summarizeText(observation, 600))
                        .rawResult(detail)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build())
                .summary("Loaded document %s".formatted(detail.getName()))
                .supplementalContext(observation)
                .references(references)
                .rawResult(detail)
                .observation(observation)
                .build();
    }

    private DocumentResponse resolveDocument(Map<String, Object> arguments, Long kbId) {
        Object docIdValue = arguments == null ? null : arguments.get("docId");
        if (docIdValue instanceof Number number) {
            Long docId = number.longValue();
            return documentService.listByKbId(kbId).stream()
                    .filter(document -> docId.equals(document.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "document_detail docId not found in current knowledge base: " + docId));
        }
        if (docIdValue instanceof String docIdText && StringUtils.hasText(docIdText) && docIdText.trim().matches("\\d+")) {
            Long docId = Long.parseLong(docIdText.trim());
            return documentService.listByKbId(kbId).stream()
                    .filter(document -> docId.equals(document.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "document_detail docId not found in current knowledge base: " + docId));
        }

        String documentName = "";
        if (arguments != null) {
            Object documentNameValue = arguments.get("documentName");
            if (documentNameValue instanceof String text && StringUtils.hasText(text)) {
                documentName = text;
            } else {
                Object queryValue = arguments.get("query");
                if (queryValue instanceof String queryText && StringUtils.hasText(queryText)) {
                    documentName = queryText;
                }
            }
        }
        if (!StringUtils.hasText(documentName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "document_detail requires docId or documentName");
        }

        final String resolvedDocumentName = documentName.trim();
        final String normalized = resolvedDocumentName.toLowerCase(Locale.ROOT);
        List<DocumentResponse> documents = documentService.listByKbId(kbId);

        return documents.stream()
                .filter(document -> StringUtils.hasText(document.getName()) && document.getName().trim().equalsIgnoreCase(resolvedDocumentName))
                .findFirst()
                .orElseGet(() -> documents.stream()
                        .filter(document -> StringUtils.hasText(document.getName()) && document.getName().toLowerCase(Locale.ROOT).contains(normalized))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "document_detail documentName not found in current knowledge base: " + resolvedDocumentName)));
    }
}
