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
public class KeywordSearchChannel implements SearchChannel {

    public static final String CHANNEL_NAME = "keyword";
    private static final Logger log = LoggerFactory.getLogger(KeywordSearchChannel.class);
    private final DocumentChunkMapper documentChunkMapper;
    private final boolean enabled;
    private final int candidateMultiplier;

    public KeywordSearchChannel(DocumentChunkMapper documentChunkMapper,
                                @Value("${app.rag.channel.keyword.enabled:true}") boolean enabled,
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
        if (!enabled || request.kbId() == null || !StringUtils.hasText(request.effectiveQuery())) {
            return List.of();
        }

        // 分词与入库侧（chunk_tokens）共用 KeywordTokenizer：两侧契约，失配即静默零召回
        String tsQuery = KeywordTokenizer.toTsQueryOr(request.effectiveQuery());
        if (tsQuery == null) {
            return List.of();
        }

        int candidateLimit = Math.max(request.topK(), request.topK() * candidateMultiplier);

        try {
            // 走 GIN 索引（tsv @@ tsquery）+ ts_rank 评分，替代旧的 24 × ILIKE 全表扫描。
            // 旧实现对 chunk_tokens 为 NULL 的历史行无法命中——启动回填（ChunkTokenBackfillRunner）
            // 负责在服务前补齐。
            List<DocumentChunk> chunks = documentChunkMapper.selectTopKByKeyword(
                    request.kbId(),
                    tsQuery,
                    request.documentIds(),
                    normalizeFileTypes(request.fileTypes()),
                    normalizeKeyword(request.documentNameKeyword()),
                    candidateLimit,
                    request.ownerUserId()
            );

            double maxRank = chunks.stream()
                    .map(DocumentChunk::getScore)
                    .filter(score -> score != null && score > 0)
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElse(1.0);

            List<SearchResult> results = new ArrayList<>(chunks.size());
            for (DocumentChunk chunk : chunks) {
                double rawScore = chunk.getScore() == null ? 0.0 : chunk.getScore();
                double normalized = maxRank > 0 ? rawScore / maxRank : 0.0;
                results.add(SearchResult.of(CHANNEL_NAME, chunk, normalized));
            }
            return results;
        } catch (RuntimeException ex) {
            log.warn("keyword search failed for kbId={}, query={}: {}",
                    request.kbId(), request.effectiveQuery(), ex.getMessage());
            return List.of();
        }
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