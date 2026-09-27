package com.nailinai.ragent.eval;

import com.nailinai.ragent.chat.retrieve.DocDiversityPostProcessor;
import com.nailinai.ragent.chat.retrieve.RerankPostProcessor;

import com.nailinai.ragent.chat.retrieve.DedupPostProcessor;
import com.nailinai.ragent.chat.retrieve.MultiChannelRetriever;
import com.nailinai.ragent.chat.retrieve.NeighborContextExpander;
import com.nailinai.ragent.chat.retrieve.SearchChannel;
import com.nailinai.ragent.chat.retrieve.SearchPostProcessor;
import com.nailinai.ragent.chat.service.RetrievalService;
import com.nailinai.ragent.chat.service.impl.RetrievalServiceImpl;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.rerank.RerankClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * RAG 检索质量评估器。
 *
 * <p>对评估集中的每条查询执行真实检索链路（含查询改写与重排），
 * 计算 recall@k / precision@k；支持查询改写与重排的消融对比
 * （on/off 四种组合），用于量化每个环节对检索质量的贡献。
 */
@Component
public class RagEvaluator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RagEvaluator.class);

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final MultiChannelRetriever defaultRetriever;
    private final List<SearchChannel> channels;
    private final double rerankSemanticWeight;
    private final double rerankLexicalWeight;
    /** 每文档席位上限（0=关闭）：与生产路径共用配置，>0 时消融各组合都挂载多样性处理器 */
    private final int diversityMaxPerDoc;
    /** cross-encoder 开关：与生产路径共用配置。曾因手动 new 用三参构造硬编码 false，
     * 导致消融的 rerank 永远是启发式、cross-encoder 从未参与评估（真机 Run A 发现） */
    private final boolean crossEncoderEnabled;
    /** 可选的 cross-encoder 客户端：手动 new 的 RerankPostProcessor 不经 Spring 注入，
     * 必须显式传递——漏传会让 cross-encoder 分支静默退回启发式（真机 Run A 发现） */
    private RerankClient rerankClient;

    @Autowired(required = false)
    public void setRerankClient(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    public RagEvaluator(EmbeddingClient embeddingClient,
                        ChatClient chatClient,
                        MultiChannelRetriever defaultRetriever,
                        List<SearchChannel> channels,
                        @Value("${app.rag.rerank.semantic-weight:0.75}") double rerankSemanticWeight,
                        @Value("${app.rag.rerank.lexical-weight:0.25}") double rerankLexicalWeight,
                        @Value("${app.rag.diversity.max-per-doc:0}") int diversityMaxPerDoc,
                        @Value("${app.rag.rerank.cross-encoder.enabled:false}") boolean crossEncoderEnabled) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.defaultRetriever = defaultRetriever;
        this.channels = channels;
        this.rerankSemanticWeight = rerankSemanticWeight;
        this.rerankLexicalWeight = rerankLexicalWeight;
        this.diversityMaxPerDoc = Math.max(0, diversityMaxPerDoc);
        this.crossEncoderEnabled = crossEncoderEnabled;
    }

    /**
     * 在指定消融组合下评估整个评估集（阈值为 0，即不过滤，测「检索能力本身」）。
     */
    public EvaluationReport evaluate(RagEvaluationSet set, boolean queryRewriteEnabled, boolean rerankEnabled) {
        return evaluate(set, queryRewriteEnabled, rerankEnabled, 0.0);
    }

    /**
     * 在指定消融组合与业务阈值下评估整个评估集。
     *
     * <p>阈值作为独立维度传入：生产路径会把 reference-distance-threshold 换算成
     * 融合分阈值过滤结果，若消融只测阈值 0，生产阈值「静默吞掉正确答案」的失败
     * 模式不会出现在任何报告里。新增 productionThreshold 组合正是为了补上这块盲区。</p>
     */
    public EvaluationReport evaluate(RagEvaluationSet set,
                                     boolean queryRewriteEnabled,
                                     boolean rerankEnabled,
                                     double scoreThreshold) {
        if (set == null || set.queries().isEmpty()) {
            return EvaluationReport.aggregate(queryRewriteEnabled, rerankEnabled, List.of());
        }
        RetrievalService service = buildRetrievalService(queryRewriteEnabled, rerankEnabled);
        double threshold = Math.max(0.0, Math.min(1.0, scoreThreshold));
        List<QueryEvaluation> queryResults = new ArrayList<>();
        for (RagEvaluationQuery query : set.queries()) {
            try {
                queryResults.add(evaluateQuery(service, set, query, threshold));
            } catch (RuntimeException ex) {
                // 单条查询的基础设施故障（embedding 供应商偶发失败）只放弃该条，
                // 不让整轮评估作废——真实供应商总会有抖动
                log.warn("evaluation query failed, skipped: {} | {}", query.question(), ex.getMessage());
            }
        }
        return EvaluationReport.aggregate(queryRewriteEnabled, rerankEnabled, queryResults);
    }

    /**
     * 按消融组合构造检索服务：查询改写开关由构造参数控制；
     * 重排开关通过是否装配 RerankPostProcessor 控制。
     *
     * <p>历史缺陷：rerank=true 分支曾直接复用 defaultRetriever，而它的重排行为
     * 取决于全局配置 app.rag.rerank.enabled——配置关闭时，消融报告里的
     * "rerank on" 一列实际测的仍是 off，结论直接错误。这里显式构造一个
     * 强制 enabled=true 的重排器（权重读同一份配置），使消融组合与全局配置解耦。
     */
    private RetrievalService buildRetrievalService(boolean rewrite, boolean rerank) {
        // 与生产路径一致的后处理器链：去重 → （席位上限）→ 重排。
        // 席位上限 >0 时消融各组合都挂载，用于线上评估验证多样性效果。
        List<SearchPostProcessor> processors = new ArrayList<>();
        processors.add(new DedupPostProcessor());
        if (diversityMaxPerDoc > 0) {
            processors.add(new DocDiversityPostProcessor(diversityMaxPerDoc));
        }
        if (rerank) {
            RerankPostProcessor rerankPostProcessor =
                    new RerankPostProcessor(true, rerankSemanticWeight, rerankLexicalWeight, crossEncoderEnabled);
            // 手动 new 的实例不经 Spring 注入：cross-encoder 客户端必须显式传递
            rerankPostProcessor.setRerankClient(rerankClient);
            processors.add(rerankPostProcessor);
        }
        MultiChannelRetriever retriever = new MultiChannelRetriever(channels, processors);
        // 评估测的是「检索命中了什么」，small-to-big 的相邻正文扩展属于服务期增强，
        // 会改变切片文本但不改变命中集合——这里显式关闭，保证指标口径稳定。
        // 改写缓存同样关闭（cache-size=0）：消融各组合要真实执行，不受同问题历史改写影响。
        return new RetrievalServiceImpl(embeddingClient, chatClient, retriever,
                NeighborContextExpander.disabled(), rewrite, 0);
    }

    private QueryEvaluation evaluateQuery(RetrievalService service, RagEvaluationSet set,
                                          RagEvaluationQuery query, double scoreThreshold) {
        int topK = set.effectiveTopK(query);

        ChatRequest request = new ChatRequest();
        request.setKbId(set.kbId());
        request.setSessionId("eval-" + UUID.randomUUID());
        request.setQuestion(query.question());
        request.setTopK(topK);

        RetrievalResult result = service.retrieve(request, topK, scoreThreshold);

        List<DocumentChunk> topChunks = result.getChunks().stream()
                .limit(topK)
                .toList();

        Set<String> retrievedNames = new LinkedHashSet<>();
        topChunks.stream()
                .map(DocumentChunk::getDocumentName)
                .filter(Objects::nonNull)
                .forEach(retrievedNames::add);

        int hitCount = (int) query.expectedDocuments().stream()
                .filter(expected -> matchAny(retrievedNames, expected))
                .count();

        EvaluationMetrics metrics = EvaluationMetrics.of(hitCount, query.expectedDocuments().size(), retrievedNames.size());
        return new QueryEvaluation(query, List.copyOf(retrievedNames), topChunks, metrics);
    }

    private boolean matchAny(Set<String> retrievedNames, String expected) {
        String normalized = expected == null ? "" : expected.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        // 只允许「期望名 ⊆ 返回名」方向：期望文档名的关键词出现在返回名中。
        // 旧实现的双向 contains 会在返回名是期望名的短子串时（如 "a.pdf"）误判命中，
        // 系统性抬高 recall/precision，消融结论失真。
        return retrievedNames.stream()
                .map(name -> name == null ? "" : name.toLowerCase())
                .anyMatch(name -> name.contains(normalized));
    }
}
