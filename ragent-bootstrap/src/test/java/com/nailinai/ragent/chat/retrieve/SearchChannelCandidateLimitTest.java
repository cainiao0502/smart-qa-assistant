package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选召回倍数（{@code app.rag.rerank.candidate-multiplier}）生效性测试。
 *
 * <p>该配置决定两个检索通道在 RRF 融合前各召回多少候选，曾一度在代码里写死为 3，
 * 导致配置形同虚设。本测试锁死「配置真的被读到」，防止回归。
 */
class SearchChannelCandidateLimitTest {

    private static final int TOP_K = 4;

    @Test
    @DisplayName("向量通道按配置倍数放大候选召回量")
    void vectorChannel_shouldHonourConfiguredMultiplier() {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(mapper.selectTopKByKbId(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(chunk()));
        VectorSearchChannel channel = new VectorSearchChannel(mapper, true, 5, 2);

        channel.search(request("[0.1,0.2]"));

        assertThat(vectorCandidateLimitOf(mapper)).isEqualTo(TOP_K * 5);
    }

    @Test
    @DisplayName("关键词通道按配置倍数放大候选召回量")
    void keywordChannel_shouldHonourConfiguredMultiplier() {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(mapper.selectTopKByKeyword(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(chunk()));
        KeywordSearchChannel channel = new KeywordSearchChannel(mapper, true, 2);

        channel.search(request("[0.1,0.2]"));

        assertThat(keywordCandidateLimitOf(mapper)).isEqualTo(TOP_K * 2);
    }

    @Test
    @DisplayName("倍数配成 0 或负数时退化为 topK，不会小于 topK")
    void multiplierBelowOne_shouldFallBackToTopK() {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(mapper.selectTopKByKbId(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(chunk()));
        VectorSearchChannel channel = new VectorSearchChannel(mapper, true, 0, 2);

        channel.search(request("[0.1,0.2]"));

        assertThat(vectorCandidateLimitOf(mapper)).isEqualTo(TOP_K);
    }

    private static SearchRequest request(String embeddingLiteral) {
        return SearchRequest.of(1L, "问题", embeddingLiteral, TOP_K, null, null, null, 1L);
    }

    private static DocumentChunk chunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setKbId(1L);
        chunk.setScore(0.9);
        return chunk;
    }

    /** 两个通道的 SQL 都把候选上限放在第 6 个参数位。 */
    private static Integer vectorCandidateLimitOf(DocumentChunkMapper mapper) {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectTopKByKbId(any(), any(), any(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static Integer keywordCandidateLimitOf(DocumentChunkMapper mapper) {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectTopKByKeyword(any(), any(), any(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }
}
