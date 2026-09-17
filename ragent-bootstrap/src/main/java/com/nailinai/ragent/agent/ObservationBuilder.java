package com.nailinai.ragent.agent;

import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.framework.util.TextTruncator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具观察结果（observation）构建器。
 *
 * <p><b>设计原则</b>（参照 pi 的 {@code harness/tools/read.ts}）：工具层负责<b>给足真实内容</b>，
 * 截断时显式告知并给出下一步动作建议；「把长内容压成一句话摘要」是上下文层（compaction）的职责，
 * 工具层不代劳——否则 {@code document_detail} 号称「深入读取」，实际只返回几百个字符，
 * 模型据此无法真正判断文档内容。
 */
@Component
public class ObservationBuilder {

    /** 文档目录最多展示的条目数 */
    private static final int MAX_CATALOG_ENTRIES = 20;

    /** 文档正文预览上限（字符） */
    private static final int DOC_CONTENT_PREVIEW_CHARS = 1_200;

    /** 单个切片预览上限（字符） */
    private static final int CHUNK_PREVIEW_CHARS = 240;

    /** 最多展示的切片数 */
    private static final int MAX_CHUNK_PREVIEWS = 8;

    public String buildCatalogObservation(List<DocumentResponse> documents) {
        if (documents == null || documents.isEmpty()) {
            return "Knowledge base catalog is empty. No uploaded documents are available.";
        }

        long zeroChunkCount = documents.stream()
                .filter(document -> document.getChunkCount() == null || document.getChunkCount() <= 0)
                .count();
        long genericNameCount = documents.stream()
                .filter(document -> isGenericDocumentName(document.getName()))
                .count();
        String summary = documents.stream()
                .limit(MAX_CATALOG_ENTRIES)
                .map(document -> "#%s %s (%s, status=%s, chunks=%s)".formatted(
                        document.getId(),
                        blankAs(document.getName(), "unknown"),
                        blankAs(document.getFileType(), "unknown"),
                        document.getStatus() == null ? "unknown" : document.getStatus().name(),
                        document.getChunkCount() == null ? "?" : document.getChunkCount()
                ))
                .collect(Collectors.joining("; "));

        StringBuilder observation = new StringBuilder("Knowledge base catalog contains %d documents. %s"
                .formatted(documents.size(), summary));
        if (documents.size() > MAX_CATALOG_ENTRIES) {
            observation.append(" [Only the first ")
                    .append(MAX_CATALOG_ENTRIES)
                    .append(" entries are listed.]");
        }
        if (zeroChunkCount > 0) {
            observation.append(" ")
                    .append("Warning: ")
                    .append(zeroChunkCount)
                    .append(" documents have zero indexed chunks, so filename-only catalog information may be misleading.");
        }
        if (genericNameCount > 0) {
            observation.append(" ")
                    .append("Warning: ")
                    .append(genericNameCount)
                    .append(" document names are generic, so you should inspect document_detail before concluding what problems the knowledge base can solve.");
        }
        return observation.toString();
    }

    /**
     * 构建单个文档的详细观察结果。
     *
     * <p>相比旧版（正文压到 260 字符、只列 3 个切片各 90 字符），现在给出：
     * 正文预览 + 切片索引 + 完整的截断元数据，让模型能真正「读到」文档。
     */
    public String buildDocumentDetailObservation(DocumentDetailResponse detailResponse) {
        if (detailResponse == null) {
            return "Document detail is unavailable.";
        }

        StringBuilder observation = new StringBuilder(
                "Document #%s %s (%s, status=%s, chunks=%s, totalChars=%s).".formatted(
                        detailResponse.getId(),
                        blankAs(detailResponse.getName(), "unknown"),
                        blankAs(detailResponse.getFileType(), "unknown"),
                        detailResponse.getStatus() == null ? "unknown" : detailResponse.getStatus().name(),
                        detailResponse.getChunkCount() == null ? "?" : detailResponse.getChunkCount(),
                        detailResponse.getContent() == null ? 0 : detailResponse.getContent().length()
                ));

        String content = detailResponse.getContent();
        if (StringUtils.hasText(content)) {
            TextTruncator.TruncationResult preview =
                    TextTruncator.truncateHead(content, DOC_CONTENT_PREVIEW_CHARS, 100);
            observation.append("\n\nContent preview:\n").append(preview.content());
            if (preview.truncated()) {
                observation.append("\n")
                        .append(preview.continuationHint())
                        .append(" Use kb_lookup with a focused query to read the sections you need.");
            }
        } else {
            observation.append("\n\nNo content was extracted from this document.")
                    .append(" If the source file is a scanned or image-heavy document, its text may not be indexed.");
        }

        var chunks = detailResponse.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            observation.append("\n\nChunk index: no indexed chunks.");
        } else {
            observation.append("\n\nChunk index (showing up to %d of %d):"
                    .formatted(MAX_CHUNK_PREVIEWS, chunks.size()));
            chunks.stream()
                    .limit(MAX_CHUNK_PREVIEWS)
                    .forEach(chunk -> observation.append("\nchunk#%s: %s".formatted(
                            chunk.getChunkIndex(),
                            TextTruncator.truncateHead(chunk.getChunkText(), CHUNK_PREVIEW_CHARS, 6).content()
                    )));
            if (chunks.size() > MAX_CHUNK_PREVIEWS) {
                observation.append("\n[%d more chunks omitted. Use kb_lookup to retrieve the content you need.]"
                        .formatted(chunks.size() - MAX_CHUNK_PREVIEWS));
            }
        }
        return observation.toString();
    }

    public String summarizeText(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String blankAs(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private boolean isGenericDocumentName(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.matches("^(readme|doc|docs|document|intro|introduce|task|note|notes|test|temp|sample|demo)(\\.[a-z0-9]+)?$")
                || normalized.matches("^[a-z]{1,12}\\.[a-z0-9]+$");
    }
}
