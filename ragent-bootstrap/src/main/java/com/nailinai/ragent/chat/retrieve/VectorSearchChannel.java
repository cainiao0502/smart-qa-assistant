package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class VectorSearchChannel implements SearchChannel {

    public static final String CHANNEL_NAME = "vector";
    private static final Logger log = LoggerFactory.getLogger(VectorSearchChannel.class);
    private final DocumentChunkMapper documentChunkMapper;
    private final boolean enabled;
    private final int candidateMultiplier;

    public VectorSearchChannel(DocumentChunkMapper documentChunkMapper,
                               @Value("${app.rag.channel.vector.enabled:true}") boolean enabled,
                               @Value("${app.rag.rerank.candidate-multiplier:3}") int candidateMultiplier) {
        this.documentChunkMapper = documentChunkMapper;
        this.enabled = enabled;
        this.candidateMultiplier = Math.max(1, candidateMultiplier);
    }

    @Override
    public String name() {
        return CHANNEL_NAME;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (!enabled || request.kbId() == null || !StringUtils.hasText(request.embeddingLiteral())) {
            return List.of();
        }

        int candidateLimit = Math.max(request.topK(), request.topK() * candidateMultiplier);

        try {
            List<DocumentChunk> chunks = documentChunkMapper.selectTopKByKbId(
                    request.kbId(),
                    request.embeddingLiteral(),
                    request.documentIds(),
                    normalizeFileTypes(request.fileTypes()),
                    normalizeKeyword(request.documentNameKeyword()),
                    candidateLimit,
                    request.ownerUserId()
            );

            List<SearchResult> results = new ArrayList<>(chunks.size());
            for (DocumentChunk chunk : chunks) {
                double score = chunk.getScore() == null ? 0.0 : chunk.getScore();
                results.add(SearchResult.of(CHANNEL_NAME, chunk, score));
            }
            return results;
        } catch (RuntimeException ex) {
            log.warn("vector search failed for kbId={}, embeddingLiteral={}: {}",
                    request.kbId(), summarizeLiteral(request.embeddingLiteral()), ex.getMessage());
            return List.of();
        }
    }

    private String summarizeLiteral(String literal) {
        if (literal == null) return "null";
        if (literal.length() <= 60) return literal;
        return literal.substring(0, 60) + "...";
    }

    private List<String> normalizeFileTypes(List<String> fileTypes) {
        if (fileTypes == null || fileTypes.isEmpty()) {
            return null;
        }
        List<String> normalized = fileTypes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return keyword.trim();
    }
}