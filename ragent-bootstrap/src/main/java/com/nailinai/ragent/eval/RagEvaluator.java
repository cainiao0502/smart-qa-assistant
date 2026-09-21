package com.nailinai.ragent.eval;

import com.nailinai.ragent.chat.retrieve.RerankPostProcessor;

import com.nailinai.ragent.chat.retrieve.DedupPostProcessor;
import com.nailinai.ragent.chat.retrieve.MultiChannelRetriever;
import com.nailinai.ragent.chat.retrieve.SearchChannel;
import com.nailinai.ragent.chat.service.RetrievalService;
import com.nailinai.ragent.chat.service.impl.RetrievalServiceImpl;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final MultiChannelRetriever defaultRetriever;
    private final List<SearchChannel> channels;
    private final double rerankSemanticWeight;
    private final double rerankLexicalWeight;

    public RagEvaluator(EmbeddingClient embeddingClient,
                        ChatClient chatClient,
                        MultiChannelRetriever defaultRetriever,
                        List<SearchChannel> channels,
                        @Value("${app.rag.rerank.semantic-weight:0.75}") double rerankSemanticWeight,
                        @Value("${app.rag.rerank.lexical-weight:0.25}") double rerankLexicalWeight) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.defaultRetriever = defaultRetriever;
        this.channels = channels;
        this.rerankSemanticWeight = rerankSemanticWeight;
        this.rerankLexicalWeight = rerankLexicalWeight;
    }

    /**
     * 在指定消融组合下评估整个评估集。
     */
    public EvaluationReport evaluate(RagEvaluationSet set, boolean queryRewriteEnabled, boolean rerankEnabled) {
        if (set == null || set.queries().isEmpty()) {
            return EvaluationReport.aggregate(queryRewriteEnabled, rerankEnabled, List.of());
        }
        RetrievalService service = buildRetrievalService(queryRewriteEnabled, rerankEnabled);
        List<QueryEvaluation> queryResults = set.queries().stream()
                .map(query -> evaluateQuery(service, set, query))
                .toList();
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
        MultiChannelRetriever retriever;
        if (rerank) {
            retriever = new MultiChannelRetriever(channels, List.of(
                    new DedupPostProcessor(),
                    new RerankPostProcessor(true, rerankSemanticWeight, rerankLexicalWeight)));
        } else {
            retriever = new MultiChannelRetriever(channels, List.of(new DedupPostProcessor()));
        }
        return new RetrievalServiceImpl(embeddingClient, chatClient, retriever, rewrite);
    }

    private QueryEvaluation evaluateQuery(RetrievalService service, RagEvaluationSet set, RagEvaluationQuery query) {
        int topK = set.effectiveTopK(query);

        ChatRequest request = new ChatRequest();
        request.setKbId(set.kbId());
        request.setSessionId("eval-" + UUID.randomUUID());
        request.setQuestion(query.question());
        request.setTopK(topK);

        RetrievalResult result = service.retrieve(request, topK, 0.0);

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
