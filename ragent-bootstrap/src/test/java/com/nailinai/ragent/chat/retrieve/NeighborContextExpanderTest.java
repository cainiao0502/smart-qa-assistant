package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * NeighborContextExpander 相邻切片扩展的单元测试。
 *
 * <p>覆盖：前后邻居拼接、chunkOverlap 重叠去重、窗口边界（首片无前邻居）、
 * 多文档分组查询、开关关闭直通、邻居查询失败降级为原文。</p>
 */
@ExtendWith(MockitoExtension.class)
class NeighborContextExpanderTest {

    @Mock
    private DocumentChunkMapper mapper;

    private NeighborContextExpander expander;

    @BeforeEach
    void setUp() {
        expander = new NeighborContextExpander(mapper, true, 1);
    }

    @Test
    @DisplayName("命中片扩展为「前邻居 + 命中片 + 后邻居」的连续文本")
    void shouldMergeBothNeighbors() {
        DocumentChunk prev = chunk(1L, 0, "第一段完整的内容。");
        DocumentChunk hit = chunk(1L, 1, "第二段被命中的内容。");
        DocumentChunk next = chunk(1L, 2, "第三段后续的内容。");
        stubNeighbors(1L, List.of(prev, hit, next));

        DocumentChunk result = expander.expand(1L, List.of(hit)).get(0);

        assertThat(result.getChunkText())
                .isEqualTo("第一段完整的内容。\n第二段被命中的内容。\n第三段后续的内容。");
    }

    @Test
    @DisplayName("chunkOverlap 重叠文本被裁掉：前邻居尾部与命中片头部重复时不重复注入")
    void shouldTrimOverlapBetweenNeighbors() {
        DocumentChunk prev = chunk(1L, 0, "前一段结尾。重叠的过渡句");
        DocumentChunk hit = chunk(1L, 1, "重叠的过渡句：命中片正文开始。");
        stubNeighbors(1L, List.of(prev, hit));

        DocumentChunk result = expander.expand(1L, List.of(hit)).get(0);

        // 「重叠的过渡句」只出现一次：命中片头部与前邻居尾部的重复被裁掉
        assertThat(result.getChunkText())
                .isEqualTo("前一段结尾。\n重叠的过渡句：命中片正文开始。");
    }

    @Test
    @DisplayName("首片没有前邻居时只拼接后邻居")
    void firstChunk_shouldOnlyAppendNext() {
        DocumentChunk hit = chunk(1L, 0, "第一片正文。");
        DocumentChunk next = chunk(1L, 1, "第二片正文。");
        stubNeighbors(1L, List.of(hit, next));

        DocumentChunk result = expander.expand(1L, List.of(hit)).get(0);

        assertThat(result.getChunkText()).isEqualTo("第一片正文。\n第二片正文。");
    }

    @Test
    @DisplayName("多文档命中按文档分组查询，各文档只取自己的邻居")
    void shouldGroupQueriesByDocId() {
        DocumentChunk hitA = chunk(1L, 5, "A 文档命中。");
        DocumentChunk neighborA = chunk(1L, 6, "A 文档后文。");
        DocumentChunk hitB = chunk(2L, 10, "B 文档命中。");
        DocumentChunk neighborB = chunk(2L, 9, "B 文档前文。");
        stubNeighbors(1L, List.of(hitA, neighborA));
        stubNeighbors(2L, List.of(hitB, neighborB));

        List<DocumentChunk> results = expander.expand(1L, List.of(hitA, hitB));

        assertThat(results.get(0).getChunkText()).isEqualTo("A 文档命中。\nA 文档后文。");
        assertThat(results.get(1).getChunkText()).isEqualTo("B 文档前文。\nB 文档命中。");
    }

    @Test
    @DisplayName("开关关闭时直通返回，不触发任何数据库查询")
    void disabled_shouldBypassExpansion() {
        NeighborContextExpander disabled = NeighborContextExpander.disabled();
        DocumentChunk hit = chunk(1L, 1, "原文。");

        List<DocumentChunk> result = disabled.expand(1L, List.of(hit));

        assertThat(result.get(0).getChunkText()).isEqualTo("原文。");
        verify(mapper, never()).selectByDocIdAndChunkIndexes(anyLong(), anyLong(), anyList());
    }

    @Test
    @DisplayName("邻居查询失败时命中片保持原文，不阻断检索主链路")
    void mapperFailure_shouldFallbackToOriginalText() {
        DocumentChunk hit = chunk(1L, 1, "原文。");
        lenient().when(mapper.selectByDocIdAndChunkIndexes(anyLong(), anyLong(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        DocumentChunk result = expander.expand(1L, List.of(hit)).get(0);

        assertThat(result.getChunkText()).isEqualTo("原文。");
    }

    private void stubNeighbors(Long docId, List<DocumentChunk> chunks) {
        lenient().when(mapper.selectByDocIdAndChunkIndexes(anyLong(), org.mockito.ArgumentMatchers.eq(docId), anyList()))
                .thenReturn(chunks);
    }

    private DocumentChunk chunk(Long docId, int index, String text) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocId(docId);
        chunk.setChunkIndex(index);
        chunk.setChunkText(text);
        return chunk;
    }
}
