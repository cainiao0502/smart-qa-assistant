package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.retrieve.ChannelStatus;
import com.nailinai.ragent.chat.retrieve.MultiChannelRetriever;
import com.nailinai.ragent.chat.retrieve.NeighborContextExpander;
import com.nailinai.ragent.chat.retrieve.SearchContext;
import com.nailinai.ragent.chat.retrieve.SearchRequest;
import com.nailinai.ragent.chat.retrieve.SearchResult;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.infra.router.RoleChatClients;
import com.nailinai.ragent.chat.service.RetrievalService;
import com.nailinai.ragent.user.context.UserIdHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final MultiChannelRetriever multiChannelRetriever;
    private final NeighborContextExpander neighborContextExpander;
    private final boolean queryRewriteEnabled;
    /** 改写结果 LRU：同一问题重复提问、agent 循环内二次检索不再重复付改写调用的钱 */
    private final Map<String, String> rewriteCache;
    /** 按角色分发：改写走 utility 角色的模型（未配置时回落主模型）。setter 注入保持构造器签名不变 */
    private RoleChatClients roleChatClients;

    public RetrievalServiceImpl(EmbeddingClient embeddingClient,
                                ChatClient chatClient,
                                MultiChannelRetriever multiChannelRetriever,
                                NeighborContextExpander neighborContextExpander,
                                @Value("${app.rag.query-rewrite.enabled:true}") boolean queryRewriteEnabled,
                                @Value("${app.rag.query-rewrite.cache-size:256}") int rewriteCacheSize) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.multiChannelRetriever = multiChannelRetriever;
        this.neighborContextExpander = neighborContextExpander;
        this.queryRewriteEnabled = queryRewriteEnabled;
        int boundedCacheSize = Math.max(0, rewriteCacheSize);
        this.rewriteCache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > boundedCacheSize;
            }
        };
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
                    .degradedChannels(List.of())
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

        List<ChannelStatus> channelStatuses = new ArrayList<>();
        List<SearchResult> results = multiChannelRetriever.retrieve(searchRequest, searchContext, channelStatuses);
        List<String> degradedChannels = channelStatuses.stream()
                .filter(ChannelStatus::degraded)
                .map(ChannelStatus::channel)
                .toList();

        // 阈值过滤放在多通道融合之后、作用于排序用的归一化分数，向量与关键词一视同仁：
        // - 重排开启时用 rerankScore（语义相对分与词法命中率的加权混合，∈[0,1]）；
        // - 重排关闭时退化为通道内归一化的 rawScore（向量通道=cosine 相似度，
        //   关键词通道=命中数/最大命中数的分数归一，并非排名归一）。
        // 旧实现只看 chunk.getScore()（仅向量 SQL 赋值），关键词通道结果恒通过，阈值形同虚设。
        List<DocumentChunk> chunks = results.stream()
                .filter(result -> fusedScore(result) >= scoreThreshold)
                .map(SearchResult::chunk)
                .toList();

        // small-to-big：阈值过滤只作用于命中切片本身，命中后再扩展相邻切片正文
        chunks = neighborContextExpander.expand(request.getKbId(), chunks);

        boolean reranked = chunks.stream().anyMatch(chunk -> chunk.getRerankScore() != null);

        return RetrievalResult.builder()
                .originalQuery(originalQuery)
                .effectiveQuery(effectiveQuery)
                .queryRewritten(queryRewritten)
                .reranked(reranked)
                .degradedChannels(degradedChannels)
                .chunks(chunks)
                .build();
    }

    /**
     * 返回用于阈值过滤的融合归一分：重排开启时为 rerankScore，
     * 否则为通道内已归一化的 rawScore（向量=cosine，关键词=排名归一分）。
     */
    private double fusedScore(SearchResult result) {
        Double rerankScore = result.chunk().getRerankScore();
        return rerankScore != null ? rerankScore : result.rawScore();
    }

    /** 查询改写属于小模型任务：配置了 ai.chat.roles.utility 时不再烧主模型 */
    @Autowired(required = false)
    public void setRoleChatClients(RoleChatClients roleChatClients) {
        this.roleChatClients = roleChatClients;
    }

    private ChatClient rewriteClient() {
        return roleChatClients != null
                ? roleChatClients.forRole(RoleChatClients.ROLE_UTILITY)
                : chatClient;
    }

    private String resolveEffectiveQuery(String originalQuery) {
        if (!queryRewriteEnabled || !StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }

        String cached = cachedRewrite(originalQuery);
        if (cached != null) {
            return cached;
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

            String rewritten = rewriteClient().chat(prompt);
            if (!StringUtils.hasText(rewritten)) {
                return originalQuery;
            }
            String normalized = rewritten.replaceAll("[\\r\\n]+", " ").trim();
            if (normalized.isEmpty()) {
                return originalQuery;
            }
            // 只缓存成功的改写：改写失败（异常/空响应）退回原文且不落缓存，
            // 避免一次瞬时故障把「原文→原文」钉在缓存里，恢复后仍然跳过改写
            cacheRewrite(originalQuery, normalized);
            return normalized;
        } catch (RuntimeException ex) {
            return originalQuery;
        }
    }

    private String cachedRewrite(String originalQuery) {
        if (rewriteCache.isEmpty()) {
            return null;
        }
        synchronized (rewriteCache) {
            return rewriteCache.get(originalQuery);
        }
    }

    private void cacheRewrite(String originalQuery, String rewritten) {
        synchronized (rewriteCache) {
            rewriteCache.put(originalQuery, rewritten);
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