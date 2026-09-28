package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.rerank.RerankClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RerankPostProcessor 的 cross-encoder 降级行为单元测试。
 *
 * <p>线上消融证明启发式重排 recall 为负收益（0.906 vs 无重排 0.969），因此
 * cross-encoder 启用但调用失败时应保持上游（去重后）排序，绝不退回启发式。</p>
 */
class RerankPostProcessorTest {

    private final SearchContext context = SearchContext.of("q", "q", 3, 0.0);

    @Test
    @DisplayName("cross-encoder 启用但调用失败：保持上游排序，不退回启发式（截断归链末位阈值处理器）")
    void crossEncoderFailure_shouldKeepUpstreamOrder() {
        List<SearchResult> inputs = List.of(
                result(1L, 0.9), result(2L, 0.8), result(3L, 0.7), result(4L, 0.6));

        RerankPostProcessor processor = new RerankPostProcessor(true, 0.75, 0.25, true);
        RerankClient failingClient = mock(RerankClient.class);
        when(failingClient.isAvailable()).thenReturn(true);
        when(failingClient.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("rerank API down"));
        processor.setRerankClient(failingClient);

        List<SearchResult> output = processor.process(inputs, context);

        // 保持上游（rawScore 降序）顺序，全部返回——top-k 截断自 C2 起归
        // ThresholdFilterPostProcessor（先筛后取前 K），本处理器不再截断
        assertThat(output).extracting(r -> r.chunk().getDocId()).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("重排关闭：直通全部候选（截断归链末位阈值处理器，C2）")
    void rerankDisabled_shouldNotTruncate() {
        List<SearchResult> inputs = List.of(result(1L, 0.9), result(2L, 0.8), result(3L, 0.7));

        RerankPostProcessor processor = new RerankPostProcessor(false, 0.75, 0.25, false);

        // context.topK() = 2，输入 3 条依旧全部直通
        List<SearchResult> output = processor.process(inputs, SearchContext.of("q", "q", 2, 0.0));

        assertThat(output).hasSize(3);
    }

    @Test
    @DisplayName("cross-encoder 成功：按 relevance_score 重排")
    void crossEncoderSuccess_shouldRerankByScore() {
        // 输入顺序 doc1(0.9), doc2(0.8)；cross-encoder 认为 doc2 更相关
        List<SearchResult> inputs = List.of(result(1L, 0.9), result(2L, 0.8));

        RerankPostProcessor processor = new RerankPostProcessor(true, 0.75, 0.25, true);
        RerankClient client = mock(RerankClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankClient.RerankResult(1, 0.95), new RerankClient.RerankResult(0, 0.10)));
        processor.setRerankClient(client);

        List<SearchResult> output = processor.process(inputs, context);

        assertThat(output).extracting(r -> r.chunk().getDocId()).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("cross-encoder 关闭：走启发式路径（旧行为保留）")
    void crossEncoderDisabled_shouldUseHeuristic() {
        List<SearchResult> inputs = List.of(result(1L, 0.9), result(2L, 0.8));

        RerankPostProcessor processor = new RerankPostProcessor(true, 0.75, 0.25, false);

        List<SearchResult> output = processor.process(inputs, context);

        assertThat(output).hasSize(2);
        assertThat(output.get(0).chunk().getRerankScore()).isNotNull();
    }

    @Test
    @DisplayName("双通道共同命中（主通道 keyword）：启发式重排取向量侧语义证据，不再被 0.25 天花板绞杀")
    void doubleHit_shouldUseVectorEvidenceRegardlessOfPrimaryChannel() {
        // 模拟去重合并输出：主通道 keyword（归一 ts_rank 1.0），向量侧 cosine 0.6
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(11L);
        chunk.setChunkText("q 切片内容"); // 查询 token "q" 命中 → lexical = 1.0
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("keyword", 1.0);
        scores.put("vector", 0.6);
        SearchResult merged = SearchResult.merged(chunk, scores);

        RerankPostProcessor processor = new RerankPostProcessor(true, 0.75, 0.25, false);
        List<SearchResult> output = processor.process(new java.util.ArrayList<>(List.of(merged)), context);

        // 语义分：候选集内唯一向量分 → min=max → 归一 1.0；rerankScore = 0.75×1.0 + 0.25×1.0 = 1.0
        // 旧实现（按主通道身份判定）语义分记 0 → 上限只剩 0.25，死于 0.6 阈值
        assertThat(output.get(0).chunk().getRerankScore()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("真 RRF（rrfWeight>0）：跨通道共同命中的证据叠加高于单通道命中")
    void rrf_shouldSumAcrossChannels() {
        DocumentChunk doubleHitChunk = new DocumentChunk();
        doubleHitChunk.setId(1L);
        doubleHitChunk.setChunkText("alpha");
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("vector", 0.9);
        scores.put("keyword", 1.0);
        SearchResult doubleHit = SearchResult.merged(doubleHitChunk, scores);

        DocumentChunk vectorOnlyChunk = new DocumentChunk();
        vectorOnlyChunk.setId(2L);
        vectorOnlyChunk.setChunkText("beta");
        SearchResult vectorOnly = SearchResult.of("vector", vectorOnlyChunk, 0.8);

        // semantic 0.5 + lexical 0.25 → rrfWeight = 0.25；查询 token 不命中任何正文 → lexical 全 0
        RerankPostProcessor processor = new RerankPostProcessor(true, 0.5, 0.25, false);
        List<SearchResult> output = processor.process(new java.util.ArrayList<>(List.of(doubleHit, vectorOnly)), context);

        SearchResult first = output.get(0);
        // 双通道：语义 0.5×1.0 + RRF (1/61 + 1/61)×0.25 ≈ 0.5082；单通道：RRF (1/62)×0.25 ≈ 0.0040
        assertThat(first.chunk().getRerankScore()).isCloseTo(0.5082, within(1e-3));
        assertThat(output.get(1).chunk().getRerankScore()).isCloseTo(0.0040, within(1e-3));
    }

    private SearchResult result(Long docId, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(System.nanoTime());
        chunk.setDocId(docId);
        chunk.setChunkText("切片内容 " + docId);
        return SearchResult.of("vector", chunk, score);
    }
}
