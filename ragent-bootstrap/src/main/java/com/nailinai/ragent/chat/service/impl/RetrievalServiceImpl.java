package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.retrieve.MultiChannelRetriever;
import com.nailinai.ragent.chat.retrieve.SearchContext;
import com.nailinai.ragent.chat.retrieve.SearchRequest;
import com.nailinai.ragent.chat.retrieve.SearchResult;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.chat.service.RetrievalService;
import com.nailinai.ragent.user.context.UserIdHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final MultiChannelRetriever multiChannelRetriever;
    private final boolean queryRewriteEnabled;

    public RetrievalServiceImpl(EmbeddingClient embeddingClient,
                                ChatClient chatClient,
                                MultiChannelRetriever multiChannelRetriever,
                                @Value("${app.rag.query-rewrite.enabled:true}") boolean queryRewriteEnabled) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.multiChannelRetriever = multiChannelRetriever;
        this.queryRewriteEnabled = queryRewriteEnabled;
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

        SearchRequest searchRequest = SearchRequest.of(
                request.getKbId(),
                effectiveQuery,
                embeddingLiteral,
                topK,
                request.getDocumentIds(),
                request.getFileTypes(),
                request.getDocumentNameKeyword(),
                // 归属过滤：只允许检索当前用户自己的知识库。取值走 UserIdHolder——
                // 检索可能运行在异步线程（agent 循环）里，直接读 Sa-Token 的 ThreadLocal 会失败。
                UserIdHolder.get()
        );

        SearchContext searchContext = SearchContext.of(
                originalQuery,
                effectiveQuery,
                topK,
                scoreThreshold
        );

        List<SearchResult> results = multiChannelRetriever.retrieve(searchRequest, searchContext);

        List<DocumentChunk> chunks = results.stream()
                .map(SearchResult::chunk)
                .filter(chunk -> chunk.getScore() == null || chunk.getScore() >= scoreThreshold)
                .toList();

        boolean reranked = chunks.stream().anyMatch(chunk -> chunk.getRerankScore() != null);

        return RetrievalResult.builder()
                .originalQuery(originalQuery)
                .effectiveQuery(effectiveQuery)
                .queryRewritten(queryRewritten)
                .reranked(reranked)
                .chunks(chunks)
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

    private String toVectorLiteral(List<Float> embedding) {
        return embedding.stream()
                .map(value -> Float.isFinite(value) ? String.valueOf(value) : "0.0")
                .collect(Collectors.joining(",", "[", "]"));
    }
}