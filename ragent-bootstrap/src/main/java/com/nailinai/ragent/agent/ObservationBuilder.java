package com.nailinai.ragent.agent;

import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ObservationBuilder {

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
                .limit(8)
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

    public String buildDocumentDetailObservation(DocumentDetailResponse detailResponse) {
        if (detailResponse == null) {
            return "Document detail is unavailable.";
        }

        String contentSummary = summarizeText(detailResponse.getContent(), 260);
        String chunkSummary = detailResponse.getChunks() == null || detailResponse.getChunks().isEmpty()
                ? "No indexed chunks."
                : detailResponse.getChunks().stream()
                .limit(3)
                .map(chunk -> "chunk#%s: %s".formatted(
                        chunk.getChunkIndex(),
                        summarizeText(chunk.getChunkText(), 90)
                ))
                .collect(Collectors.joining(" | "));

        return "Document #%s %s (%s, status=%s, chunks=%s). Content summary: %s. Chunk summary: %s".formatted(
                detailResponse.getId(),
                blankAs(detailResponse.getName(), "unknown"),
                blankAs(detailResponse.getFileType(), "unknown"),
                detailResponse.getStatus() == null ? "unknown" : detailResponse.getStatus().name(),
                detailResponse.getChunkCount() == null ? "?" : detailResponse.getChunkCount(),
                blankAs(contentSummary, "No content extracted."),
                chunkSummary
        );
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
