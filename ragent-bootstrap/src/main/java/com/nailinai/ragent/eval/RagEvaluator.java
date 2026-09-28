package com.nailinai.ragent.eval;

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
    private final List<SearchChannel> channels;
    /**
     * Spring 装配的生产处理器链（去重 → 席位上限 → 重排，按 order 排序）。
     * 评估链路直接复用它再做消融过滤——这是「评估与生产同构」的保障：
     * 任何手动 new 处理器的做法都会重现 crossEncoderEnabled 硬编码、rerankClient
     * 漏传一类的静默失真缺陷。
     */
    private final List<SearchPostProcessor> productionProcessors;

    /**
     * 连续失败条数达到该值即判定为系统性故障（权限/配置/网络断链），中止整轮评估。
     * 单条失败是供应商抖动，只跳过该条；连续失败被逐条吞掉会让报告呈现为
     * 「召回全零」——09-16 教训：权限拦截被误读成检索能力崩塌。
     */
    private int maxConsecutiveFailures = 3;

    public RagEvaluator(EmbeddingClient embeddingClient,
                        ChatClient chatClient,
                        List<SearchChannel> channels,
                        List<SearchPostProcessor> productionProcessors) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.channels = channels;
        this.productionProcessors = productionProcessors == null ? List.of() : List.copyOf(productionProcessors);
    }

    @Autowired
    public void setMaxConsecutiveFailures(
            @Value("${app.eval.max-consecutive-failures:3}") int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
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
        int consecutiveFailures = 0;
        for (RagEvaluationQuery query : set.queries()) {
            try {
                queryResults.add(evaluateQuery(service, set, query, threshold));
                consecutiveFailures = 0;
            } catch (RuntimeException ex) {
                // 单条查询的基础设施故障（embedding 供应商偶发失败）只放弃该条，
                // 不让整轮评估作废——真实供应商总会有抖动
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    throw new EvaluationAbortedException("连续 " + consecutiveFailures
                            + " 条查询失败，判定为系统性故障而非供应商抖动，中止整轮评估"
                            + "（避免权限/配置类故障被逐条吞掉后伪装成「召回全零」）。"
                            + "最后一条失败原因: " + query.question() + " | " + ex.getMessage());
                }
                log.warn("evaluation query failed, skipped: {} | {}", query.question(), ex.getMessage());
            }
        }
        return EvaluationReport.aggregate(queryRewriteEnabled, rerankEnabled, queryResults);
    }

    /**
     * 按消融组合构造检索服务。
     *
     * <p><b>同构原则</b>：处理器链直接复用 Spring 装配的生产链（{@code productionProcessors}），
     * 消融开关只做「过滤」——rerank=false 时从链中移除 RerankPostProcessor，而不是手工重建。
     * 这样生产链上新增/调整处理器时评估自动跟随，杜绝「手动 new 绕过 Spring 装配」系列缺陷
     * （crossEncoderEnabled 硬编码 false、rerankClient 漏传，均曾在真机导致评估静默失真）。</p>
     *
     * <p>查询改写开关由 RetrievalServiceImpl 构造参数控制；small-to-big 相邻扩展与改写缓存
     * 属于服务期增强，评估显式关闭以保证指标口径稳定。</p>
     */
    private RetrievalService buildRetrievalService(boolean rewrite, boolean rerank) {
        List<SearchPostProcessor> processors = new ArrayList<>(productionProcessors);
        if (!rerank) {
            processors.removeIf(processor -> processor instanceof RerankPostProcessor);
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
