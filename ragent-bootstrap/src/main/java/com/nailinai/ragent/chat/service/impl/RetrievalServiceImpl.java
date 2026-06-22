package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.chat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-z0-9]+");

    private final EmbeddingClient embeddingClient;
    private final DocumentChunkMapper documentChunkMapper;
    private final ChatClient chatClient;
    private final boolean queryRewriteEnabled;
    private final boolean rerankEnabled;
    private final int rerankCandidateMultiplier;
    private final double rerankSemanticWeight;
    private final double rerankLexicalWeight;

    public RetrievalServiceImpl(EmbeddingClient embeddingClient,
                                DocumentChunkMapper documentChunkMapper,
                                ChatClient chatClient,
                                @Value("${app.rag.query-rewrite.enabled:true}") boolean queryRewriteEnabled,
                                @Value("${app.rag.rerank.enabled:true}") boolean rerankEnabled,
                                @Value("${app.rag.rerank.candidate-multiplier:3}") int rerankCandidateMultiplier,
                                @Value("${app.rag.rerank.semantic-weight:0.75}") double rerankSemanticWeight,
                                @Value("${app.rag.rerank.lexical-weight:0.25}") double rerankLexicalWeight) {
        this.embeddingClient = embeddingClient;
        this.documentChunkMapper = documentChunkMapper;
        this.chatClient = chatClient;
        this.queryRewriteEnabled = queryRewriteEnabled;
        this.rerankEnabled = rerankEnabled;
        this.rerankCandidateMultiplier = Math.max(1, rerankCandidateMultiplier);
        this.rerankSemanticWeight = normalizeWeight(rerankSemanticWeight);
        this.rerankLexicalWeight = normalizeWeight(rerankLexicalWeight);
    }

    @Override
    public RetrievalResult retrieve(ChatRequest request, int defaultTopK, double defaultScoreThreshold) {
        int topK = resolveTopK(request.getTopK(), defaultTopK);
        double scoreThreshold = resolveScoreThreshold(request.getScoreThreshold(), defaultScoreThreshold);
        String originalQuery = request.getQuestion().trim();
        if (request.getKbId() == null) {
            return RetrievalResult.builder()
                    .originalQuery(originalQuery)
                    .effectiveQuery(originalQuery)
                    .queryRewritten(false)
                    .reranked(false)
                    .chunks(List.of())
                    .build();
        }
        String effectiveQuery = resolveEffectiveQuery(originalQuery);
        boolean queryRewritten = !effectiveQuery.equals(originalQuery);

        List<Float> embedding = embeddingClient.embed(effectiveQuery);
        String embeddingLiteral = toVectorLiteral(embedding);
        int candidateLimit = rerankEnabled ? Math.max(topK, topK * rerankCandidateMultiplier) : topK;

        List<DocumentChunk> initialChunks = documentChunkMapper.selectTopKByKbId(
                request.getKbId(),
                embeddingLiteral,
                request.getDocumentIds(),
                normalizeFileTypes(request.getFileTypes()),
                normalizeKeyword(request.getDocumentNameKeyword()),
                candidateLimit
        ).stream()
                .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= scoreThreshold)
                .collect(Collectors.toCollection(ArrayList::new));

        List<DocumentChunk> finalChunks = rerankEnabled
                ? rerankChunks(initialChunks, effectiveQuery, topK, request, scoreThreshold)
                : initialChunks.stream()
                .limit(topK)
                .peek(chunk -> {
                    chunk.setRerankScore(chunk.getScore());
                    chunk.setHitReason(buildHitReason(chunk, request, scoreThreshold, 0.0, false, queryRewritten, originalQuery, effectiveQuery));
                })
                .toList();

        return RetrievalResult.builder()
                .originalQuery(originalQuery)
                .effectiveQuery(effectiveQuery)
                .queryRewritten(queryRewritten)
                .reranked(rerankEnabled)
                .chunks(finalChunks)
                .build();
    }

    private String resolveEffectiveQuery(String originalQuery) {
        if (!queryRewriteEnabled || !StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }

        try {
            String prompt = """
                    You are a retrieval query rewriter for a RAG system.
                    Rewrite the user's question into one concise search query that is better for semantic retrieval.
                    Rules:
                    - Keep the original intent unchanged.
                    - Expand abbreviations or vague wording only when it helps retrieval.
                    - Return one line only.
                    - Do not add explanations, numbering, quotes, or markdown.

                    User question:
                    %s
                    """.formatted(originalQuery.strip());

            String rewritten = chatClient.chat(prompt);
            if (!StringUtils.hasText(rewritten)) {
                return originalQuery;
            }
            String normalized = rewritten.replaceAll("[\\r\\n]+", " ").trim();
            return normalized.isEmpty() ? originalQuery : normalized;
        } catch (RuntimeException ex) {
            return originalQuery;
        }
    }

    private List<DocumentChunk> rerankChunks(List<DocumentChunk> chunks,
                                             String effectiveQuery,
                                             int topK,
                                             ChatRequest request,
                                             double scoreThreshold) {
        Set<String> queryTokens = tokenize(effectiveQuery);

        return chunks.stream()
                .peek(chunk -> {
                    double lexicalScore = computeLexicalScore(queryTokens, chunk);
                    double semanticScore = chunk.getScore() == null ? 0.0 : chunk.getScore();
                    double rerankScore = semanticScore * rerankSemanticWeight + lexicalScore * rerankLexicalWeight;
                    chunk.setRerankScore(rerankScore);
                    chunk.setHitReason(buildHitReason(
                            chunk,
                            request,
                            scoreThreshold,
                            lexicalScore,
                            true,
                            !effectiveQuery.equals(request.getQuestion().trim()),
                            request.getQuestion().trim(),
                            effectiveQuery
                    ));
                })
                .sorted(Comparator
                        .comparing(DocumentChunk::getRerankScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DocumentChunk::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();
    }

    private double computeLexicalScore(Set<String> queryTokens, DocumentChunk chunk) {
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> chunkTokens = tokenize((chunk.getDocumentName() == null ? "" : chunk.getDocumentName()) + " " + chunk.getChunkText());
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

    private int resolveTopK(Integer requestedTopK, int defaultTopK) {
        if (requestedTopK == null) {
            return defaultTopK;
        }
        return Math.max(1, Math.min(requestedTopK, 20));
    }

    private double resolveScoreThreshold(Double requestedScoreThreshold, double defaultScoreThreshold) {
        if (requestedScoreThreshold == null) {
            return defaultScoreThreshold;
        }
        return Math.max(0.0, Math.min(requestedScoreThreshold, 1.0));
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

    private String buildHitReason(DocumentChunk chunk,
                                  ChatRequest request,
                                  double scoreThreshold,
                                  double lexicalScore,
                                  boolean reranked,
                                  boolean queryRewritten,
                                  String originalQuery,
                                  String effectiveQuery) {
        List<String> reasons = new ArrayList<>();

        if (chunk.getScore() != null) {
            reasons.add("向量相似度 " + formatDecimal(chunk.getScore()) + "，高于阈值 " + formatDecimal(scoreThreshold));
        }
        if (reranked && chunk.getRerankScore() != null) {
            reasons.add("重排分 " + formatDecimal(chunk.getRerankScore()) + "，关键词匹配度 " + formatDecimal(lexicalScore));
        }
        if (StringUtils.hasText(request.getDocumentNameKeyword())) {
            reasons.add("文档名匹配关键字“" + request.getDocumentNameKeyword().trim() + "”");
        }
        if (request.getFileTypes() != null && !request.getFileTypes().isEmpty() && StringUtils.hasText(chunk.getFileType())) {
            reasons.add("命中文档类型 " + chunk.getFileType().toUpperCase(Locale.ROOT));
        }
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            reasons.add("命中指定文档范围");
        }
        if (queryRewritten) {
            reasons.add("检索改写：\"" + originalQuery + "\" -> \"" + effectiveQuery + "\"");
        }

        return String.join("；", reasons);
    }

    private double normalizeWeight(double weight) {
        if (weight < 0) {
            return 0.0;
        }
        if (weight > 1) {
            return 1.0;
        }
        return weight;
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String toVectorLiteral(List<Float> embedding) {
        return embedding.stream()
                .map(value -> Float.isFinite(value) ? String.valueOf(value) : "0.0")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
