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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_.]+|[\\u4e00-\\u9fff]+");
    private static final int MAX_TOKENS = 24;

    /**
     * 查询分词：英文/数字词整词保留，中文片段切 2-gram。
     *
     * <p>历史教训：原实现直接把整句丢给 plainto_tsquery('simple', ...)，而 PG 的
     * simple 分词器按空格切分，中文整段会成为单个超长 token（无法命中任何 chunk），
     * 导致关键词通道对中文内容完全失效。应用层分词后按 ILIKE + 命中数打分，
     * 不依赖 PG 中文分词扩展。
     */
    private List<String> tokenize(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String part = matcher.group().toLowerCase(Locale.ROOT);
            boolean isChinese = part.codePoints().allMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
            if (isChinese && part.length() > 1) {
                for (int i = 0; i + 2 <= part.length() && tokens.size() < MAX_TOKENS; i++) {
                    tokens.add(part.substring(i, i + 2));
                }
            } else {
                tokens.add(part);
            }
        }
        return tokens.stream().distinct().limit(MAX_TOKENS).toList();
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (!enabled || request.kbId() == null || !StringUtils.hasText(request.effectiveQuery())) {
            return List.of();
        }

        List<String> tokens = tokenize(request.effectiveQuery());
        if (tokens.isEmpty()) {
            return List.of();
        }

        int candidateLimit = Math.max(request.topK(), request.topK() * candidateMultiplier);

        try {
            List<DocumentChunk> chunks = documentChunkMapper.selectTopKByKeyword(
                    request.kbId(),
                    tokens,
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