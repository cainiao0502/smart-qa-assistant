package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RerankPostProcessor implements SearchPostProcessor {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-z0-9]+");
    private static final int RRF_K = 60;

    private final boolean enabled;
    private final double semanticWeight;
    private final double lexicalWeight;

    public RerankPostProcessor(@Value("${app.rag.rerank.enabled:true}") boolean enabled,
                               @Value("${app.rag.rerank.semantic-weight:0.75}") double semanticWeight,
                               @Value("${app.rag.rerank.lexical-weight:0.25}") double lexicalWeight) {
        this.enabled = enabled;
        this.semanticWeight = normalizeWeight(semanticWeight);
        this.lexicalWeight = normalizeWeight(lexicalWeight);
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (!enabled || inputs == null || inputs.isEmpty()) {
            return inputs == null ? List.of() : inputs.stream().limit(context.topK()).toList();
        }

        Set<String> queryTokens = tokenize(context.effectiveQuery());

        return inputs.stream()
                .peek(result -> {
                    DocumentChunk chunk = result.chunk();
                    double semanticScore = chunk.getScore() == null ? result.rawScore() : chunk.getScore();
                    double lexicalScore = computeLexicalScore(queryTokens, chunk);
                    double rrfScore = computeRrfScore(result, inputs);
                    double rerankScore = semanticScore * semanticWeight
                            + lexicalScore * lexicalWeight
                            + rrfScore * (1.0 - semanticWeight - lexicalWeight);
                    chunk.setRerankScore(rerankScore);
                    chunk.setHitReason(buildHitReason(chunk, context, lexicalScore, result.channel()));
                })
                .sorted(Comparator
                        .comparing(SearchResult::chunk,
                                Comparator.comparing(DocumentChunk::getRerankScore,
                                        Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(SearchResult::rawScore, Comparator.reverseOrder()))
                .limit(context.topK())
                .toList();
    }

    private double computeRrfScore(SearchResult target, List<SearchResult> all) {
        double rrf = 0.0;
        for (SearchResult result : all) {
            if (!result.channel().equals(target.channel())) {
                continue;
            }
            int rank = 0;
            for (SearchResult r : all) {
                if (r.channel().equals(result.channel()) && r.rawScore() > result.rawScore()) {
                    rank++;
                }
            }
            if (result.chunkId() != null && result.chunkId().equals(target.chunkId())) {
                rrf += 1.0 / (RRF_K + rank + 1);
            }
        }
        return rrf;
    }

    private double computeLexicalScore(Set<String> queryTokens, DocumentChunk chunk) {
        if (queryTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> chunkTokens = tokenize((chunk.getDocumentName() == null ? "" : chunk.getDocumentName())
                + " " + chunk.getChunkText());
        if (chunkTokens.isEmpty()) {
            return 0.0;
        }
        long hitCount = queryTokens.stream().filter(chunkTokens::contains).count();
        return hitCount / (double) queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String buildHitReason(DocumentChunk chunk, SearchContext context, double lexicalScore, String channel) {
        List<String> reasons = new java.util.ArrayList<>();
        if (chunk.getScore() != null) {
            reasons.add("向量相似度 " + formatDecimal(chunk.getScore())
                    + "，高于阈值 " + formatDecimal(context.scoreThreshold()));
        }
        if (chunk.getRerankScore() != null) {
            reasons.add("重排分 " + formatDecimal(chunk.getRerankScore())
                    + "，关键词匹配度 " + formatDecimal(lexicalScore));
        }
        reasons.add("召回通道: " + channel);
        if (!context.originalQuery().equals(context.effectiveQuery())) {
            reasons.add("检索改写：\"" + context.originalQuery() + "\" -> \"" + context.effectiveQuery() + "\"");
        }
        return String.join("；", reasons);
    }

    private double normalizeWeight(double weight) {
        if (weight < 0) return 0.0;
        if (weight > 1) return 1.0;
        return weight;
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}