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

    /**
     * RRF 排名分量权重 = 1 - semanticWeight - lexicalWeight，恒 >= 0。
     * 注意默认配置（0.75 + 0.25）下该权重为 0，RRF 分量不参与打分；
     * 想启用 RRF 需把 semantic-weight + lexical-weight 配置到小于 1。
     */
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

    /**
     * 重排：给候选写 rerankScore / hitReason 并<b>按分降序排列</b>，但不做截断。
     *
     * <p>截断（{@code limit(topK)}）自 C2 起归属 {@link ThresholdFilterPostProcessor}
     * （链末位），实现「先筛后取前 K」：被阈值否掉的席位可由更靠后的候选回填，且
     * {@code rerank.enabled=false} 时链上依然存在截断点。本处理器只负责排序语义。</p>
     */
    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (!enabled || inputs == null || inputs.isEmpty()) {
            return inputs == null ? List.of() : List.copyOf(inputs);
        }

        Set<String> queryTokens = tokenize(context.effectiveQuery());

        // 语义分量归一路径：只有向量通道的命中带 cosine 相似度。关键词通道的 score 是
        // 归一 ts_rank，量纲完全不同，绝不能冒充语义分（旧实现对 score=null 的结果回退
        // rawScore 充当语义分，与 cosine 直接混算）。去重合并后，双通道共同命中的切片
        // 即使主通道是 keyword，其向量侧 cosine 仍保留在 channelScores 里——这里按
        // scoreFrom(vector) 取证，而不是主通道身份，共同命中不再被当成「无语义证据」。
        DoubleSummaryStatistics semanticStats = inputs.stream()
                .map(r -> r.scoreFrom(VectorSearchChannel.CHANNEL_NAME))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

        // cross-encoder 精排：配置开启且客户端可用时，用「query+候选文本」成对打分替代
        // 向量 cosine 启发式作为语义分量来源（对所有通道统一适用，不再只有向量通道有语义证据）。
        Map<Integer, Double> crossEncoderScores = tryCrossEncoderRerank(inputs, context);
        if (crossEncoderScores != null) {
            // 向链路下游广播 CE 实际生效：ThresholdFilterPostProcessor 据此切换阈值量纲
            context.addFlag(SearchContext.FLAG_CROSS_ENCODER);
        }
        if (crossEncoderEnabled && crossEncoderScores == null) {
            // cross-encoder 启用但调用失败（供应商异常/超时）：保持上游（去重后）排序，
            // 不做启发式重排——2026-09-27 线上消融证明启发式重排 recall 为负收益
            // （0.906 vs 无重排 0.969），失败时退回启发式等于主动降级检索质量。
            // 截断交给链末位的 ThresholdFilterPostProcessor。
            log.warn("cross-encoder rerank enabled but unavailable, falling back to un-reranked order");
            return List.copyOf(inputs);
        }

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
            chunk.setHitReason(buildHitReason(chunk, context, lexicalScore, result, crossEncoderScores != null));
        }

        // 只排序不截断：截断在 ThresholdFilterPostProcessor（先筛后取前 K）
        return inputsList.stream()
                .sorted(Comparator
                        .comparing(SearchResult::chunk,
                                Comparator.comparing(DocumentChunk::getRerankScore,
                                        Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(SearchResult::rawScore, Comparator.reverseOrder()))
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
     * 语义分归一：向量通道命中（无论是否为主通道——去重合并后双通道命中保留全部身份）
     * 的 cosine 在候选集内 min-max 归一到 [0,1]；无向量证据的命中记 0，不冒充语义分。
     */
    private double normalizedSemanticScore(SearchResult result, DoubleSummaryStatistics stats) {
        Double score = result.scoreFrom(VectorSearchChannel.CHANNEL_NAME);
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

    /**
     * 真 RRF：对切片的<strong>每个</strong>命中通道求 1/(k + 通道内排名) 再求和——
     * 跨通道共同命中得到证据叠加（双通道 ≈ 2×单通道），这才符合 Reciprocal Rank Fusion
     * 的定义。旧实现只在同通道内计排名（去重后同 chunkId 只剩一条，产出恒 ≤1/61），
     * 是「假 RRF」；且逐目标重算三层嵌套为 O(n³)，这里预聚合为 O(n²)。
     * 通道内排名与 DedupPostProcessor 同口径：严格大于者 +1（标准竞赛排名）。
     */
    private double computeRrfScore(SearchResult target, List<SearchResult> all) {
        double rrf = 0.0;
        for (Map.Entry<String, Double> hit : target.channelScores().entrySet()) {
            int rank = 1;
            for (SearchResult other : all) {
                Double score = other.scoreFrom(hit.getKey());
                if (score != null && score > hit.getValue()) {
                    rank++;
                }
            }
            rrf += 1.0 / (RRF_K + rank);
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

    private String buildHitReason(DocumentChunk chunk, SearchContext context, double lexicalScore,
                                  SearchResult result, boolean crossEncoderActive) {
        List<String> reasons = new java.util.ArrayList<>();
        if (crossEncoderActive) {
            // cross-encoder 模式：语义分量来自重排模型而非向量 cosine，
            // 此时「向量相似度高于阈值」的表述是错的（真正过阈值的是融合后的 rerankScore）
            reasons.add("cross-encoder 重排分 " + formatDecimal(chunk.getRerankScore())
                    + "，关键词匹配度 " + formatDecimal(lexicalScore));
        } else {
            Double vectorScore = result.scoreFrom(VectorSearchChannel.CHANNEL_NAME);
            if (vectorScore != null) {
                // 只呈现证据值，不断言「高于阈值」：阈值门槛作用于融合分，且由
                // ThresholdFilterPostProcessor 按量纲分标定，对关键词独有命中并不适用
                reasons.add("向量相似度 " + formatDecimal(vectorScore));
            }
            if (chunk.getRerankScore() != null) {
                reasons.add("重排分 " + formatDecimal(chunk.getRerankScore())
                        + "，关键词匹配度 " + formatDecimal(lexicalScore));
            }
        }
        // 列出全部命中通道：双通道共同命中是跨通道证据，对调试可见
        reasons.add("召回通道: " + String.join("+", result.channelScores().keySet()));
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