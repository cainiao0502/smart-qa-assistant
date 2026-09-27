package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.rerank.RerankClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("cross-encoder 启用但调用失败：保持上游排序并截断 top-k，不退回启发式")
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

        // 保持上游（rawScore 降序）顺序，仅截断 top-k
        assertThat(output).extracting(r -> r.chunk().getDocId()).containsExactly(1L, 2L, 3L);
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

    private SearchResult result(Long docId, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(System.nanoTime());
        chunk.setDocId(docId);
        chunk.setChunkText("切片内容 " + docId);
        return SearchResult.of("vector", chunk, score);
    }
}
