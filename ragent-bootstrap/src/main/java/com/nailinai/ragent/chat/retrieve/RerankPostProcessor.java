package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.rerank.RerankClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.DoubleSummaryStatistics;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RerankPostProcessor implements SearchPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(RerankPostProcessor.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-z0-9]+");
    private static final int RRF_K = 60;

    private final boolean enabled;
    private final double semanticWeight;
    private final double lexicalWeight;
    private final boolean crossEncoderEnabled;

    /** RRF 排名分量权重 = 1 - semanticWeight - lexicalWeight，恒 >= 0 */
    /** RRF 排名分量权重 = 1 - semanticWeight - lexicalWeight，恒 >= 0 */
    private final double rrfWeight;
    /** 可选的 cross-encoder 客户端：未启用/未配置时为 null，走启发式重排 */
    private RerankClient rerankClient;

    public RerankPostProcessor(@Value("${app.rag.rerank.enabled:true}") boolean enabled,
                               @Value("${app.rag.rerank.semantic-weight:0.75}") double semanticWeight,
                               @Value("${app.rag.rerank.lexical-weight:0.25}") double lexicalWeight) {
        this(enabled, semanticWeight, lexicalWeight, false);
    }

    @Autowired
    public RerankPostProcessor(@Value("${app.rag.rerank.enabled:true}") boolean enabled,
                               @Value("${app.rag.rerank.semantic-weight:0.75}") double semanticWeight,
                               @Value("${app.rag.rerank.lexical-weight:0.25}") double lexicalWeight,
                               @Value("${app.rag.rerank.cross-encoder.enabled:false}") boolean crossEncoderEnabled) {
        this.enabled = enabled;
        this.crossEncoderEnabled = crossEncoderEnabled;
        double semantic = normalizeWeight(semanticWeight);
        double lexical = normalizeWeight(lexicalWeight);
        // 三路权重之和必须为 1 且每路 >= 0：旧实现只各自 clamp [0,1]，
        // semantic=0.9 + lexical=0.25 时 RRF 权重为 -0.15，共同命中反而被惩罚。
        // 超出 1 时按比例归一，保持两路相对重要性不变。
        double sum = semantic + lexical;
        if (sum > 1.0) {
            semantic = semantic / sum;
            lexical = lexical / sum;
        }
        this.semanticWeight = semantic;
        this.lexicalWeight = lexical;
        this.rrfWeight = 1.0 - semantic - lexical;
    }

    /** 注入可选的 cross-encoder 客户端（ai.rerank.* 未配置时该 bean 为 unavailable 实现） */
    @Autowired(required = false)
    public void setRerankClient(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
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

        // 语义分量归一路径：只有向量通道的结果带 cosine 相似度（chunk.getScore()）。
        // 关键词通道的 chunk.getScore() 是命中计数/排名，量纲完全不同，绝不能冒充语义分
        // （旧实现对 score=null 的结果回退 rawScore 充当语义分，与 cosine 直接混算）。
        // 先对候选集内向量通道的原始分做 min-max 归一到 [0,1] 再加权；
        // 非向量通道没有语义证据，语义分量记 0，靠词法分与 RRF 排名参与排序。
        DoubleSummaryStatistics semanticStats = inputs.stream()
                .filter(r -> VectorSearchChannel.CHANNEL_NAME.equals(r.channel()))
                .map(r -> r.chunk().getScore())
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

        // cross-encoder 精排：配置开启且客户端可用时，用「query+候选文本」成对打分替代
        // 向量 cosine 启发式作为语义分量来源（对所有通道统一适用，不再只有向量通道有语义证据）。
        // 调用失败/超时自动降级回启发式，绝不阻塞检索主链路。
        Map<Integer, Double> crossEncoderScores = tryCrossEncoderRerank(inputs, context);

        List<SearchResult> inputsList = List.copyOf(inputs);
        for (int index = 0; index < inputsList.size(); index++) {
            SearchResult result = inputsList.get(index);
            DocumentChunk chunk = result.chunk();
            double semanticScore = crossEncoderScores != null
                    ? crossEncoderScores.getOrDefault(index, 0.0)
                    : normalizedSemanticScore(result, semanticStats);
            // 词法分量归一路径：computeLexicalScore 是命中 token 占比，天然 ∈ [0,1]，无需再归一。
            double lexicalScore = computeLexicalScore(queryTokens, chunk);
            double rrfScore = computeRrfScore(result, inputs);
            // 三路权重均 >= 0 且和为 1（构造器已保证），RRF 只加不减，不再惩罚共同命中。
            double rerankScore = semanticScore * semanticWeight
                    + lexicalScore * lexicalWeight
                    + rrfScore * rrfWeight;
            chunk.setRerankScore(rerankScore);
            chunk.setHitReason(buildHitReason(chunk, context, lexicalScore, result.channel()));
        }

        return inputsList.stream()
                .sorted(Comparator
                        .comparing(SearchResult::chunk,
                                Comparator.comparing(DocumentChunk::getRerankScore,
                                        Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(SearchResult::rawScore, Comparator.reverseOrder()))
                .limit(context.topK())
                .toList();
    }

    /**
     * 调用 cross-encoder 重排模型给候选打分。
     *
     * @return index → 相关度分（bge-reranker 的 sigmoid 输出，∈[0,1]）；不可用或失败返回 null（走启发式）
     */
    private Map<Integer, Double> tryCrossEncoderRerank(List<SearchResult> inputs, SearchContext context) {
        if (!crossEncoderEnabled || rerankClient == null || !rerankClient.isAvailable() || inputs.size() < 2) {
            return null;
        }
        List<String> documents = inputs.stream()
                .map(result -> {
                    DocumentChunk chunk = result.chunk();
                    String name = chunk.getDocumentName() == null ? "" : chunk.getDocumentName() + "\n";
                    return name + chunk.getChunkText();
                })
                .toList();
        try {
            long start = System.currentTimeMillis();
            List<RerankClient.RerankResult> results =
                    rerankClient.rerank(context.effectiveQuery(), documents, inputs.size());
            Map<Integer, Double> scores = new java.util.HashMap<>();
            for (RerankClient.RerankResult result : results) {
                scores.put(result.index(), result.score());
            }
            log.info("cross-encoder rerank applied: provider={}, candidates={}, took={}ms",
                    rerankClient.name(), inputs.size(), System.currentTimeMillis() - start);
            return scores;
        } catch (Exception ex) {
            log.warn("cross-encoder rerank failed, falling back to heuristic rerank: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 语义分归一：向量通道的 cosine 原始分在候选集内 min-max 归一到 [0,1]；
     * 非向量通道（或 score 缺失）没有语义证据，记 0，不冒充语义分。
     */
    private double normalizedSemanticScore(SearchResult result, DoubleSummaryStatistics stats) {
        if (!VectorSearchChannel.CHANNEL_NAME.equals(result.channel())) {
            return 0.0;
        }
        Double score = result.chunk().getScore();
        if (score == null) {
            return 0.0;
        }
        double min = stats.getMin();
        double max = stats.getMax();
        if (max <= min) {
            // 候选集中语义分全部相同（或只有一个向量结果），无法区分高低，给满分避免误伤
            return 1.0;
        }
        return (score - min) / (max - min);
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