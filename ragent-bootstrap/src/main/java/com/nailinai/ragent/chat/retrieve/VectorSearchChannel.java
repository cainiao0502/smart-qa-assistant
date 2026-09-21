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

@Component
public class VectorSearchChannel implements SearchChannel {

    public static final String CHANNEL_NAME = "vector";
    private static final Logger log = LoggerFactory.getLogger(VectorSearchChannel.class);
    private final DocumentChunkMapper documentChunkMapper;
    private final boolean enabled;
    private final int candidateMultiplier;
    /** 期望的向量维度，对应 document_chunk.embedding 的 VECTOR(维度) 定义 */
    private final int expectedEmbeddingDim;

    public VectorSearchChannel(DocumentChunkMapper documentChunkMapper,
                               @Value("${app.rag.channel.vector.enabled:true}") boolean enabled,
                               @Value("${app.rag.rerank.candidate-multiplier:3}") int candidateMultiplier,
                               @Value("${app.rag.embedding-dim:1024}") int expectedEmbeddingDim) {
        this.documentChunkMapper = documentChunkMapper;
        this.enabled = enabled;
        this.candidateMultiplier = Math.max(1, candidateMultiplier);
        this.expectedEmbeddingDim = Math.max(1, expectedEmbeddingDim);
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

        // 维度守卫：查询向量与库内向量维度不符时，SQL 必然抛错或全军覆没。
        // 与其吞掉异常静默返回空（检索质量悄悄劣化无人察觉），不如快速跳过并打高可见度日志。
        int literalDim = countDimensions(request.embeddingLiteral());
        if (literalDim != expectedEmbeddingDim) {
            log.error("vector channel skipped: query embedding dimension {} does not match expected {} "
                            + "(embedding model changed? restore the model or re-index all documents). kbId={}",
                    literalDim, expectedEmbeddingDim, request.kbId());
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

    /** 从 pgvector 字面量 [a,b,c] 统计向量维度 */
    private int countDimensions(String literal) {
        if (literal == null || literal.length() < 2) {
            return 0;
        }
        int commas = 0;
        for (int i = 0; i < literal.length(); i++) {
            if (literal.charAt(i) == ',') {
                commas++;
            }
        }
        return commas + 1;
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
