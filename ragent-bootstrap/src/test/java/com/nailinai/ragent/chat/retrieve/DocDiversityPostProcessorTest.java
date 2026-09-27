package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocDiversityPostProcessor 每文档席位上限的单元测试。
 */
class DocDiversityPostProcessorTest {

    private final SearchContext context = SearchContext.of("q", "q", 4, 0.0);

    @Test
    @DisplayName("maxPerDoc=2：大文档只保留前 2 席，小文档进入结果")
    void shouldCapSeatsPerDocument() {
        // 输入已按分数降序：doc1 占前 3 席，doc2 的切片排第 4
        List<SearchResult> inputs = List.of(
                result(1L, 0.9), result(1L, 0.8), result(1L, 0.7),
                result(2L, 0.6), result(1L, 0.5));

        List<SearchResult> output = new DocDiversityPostProcessor(2).process(inputs, context);

        // doc1 的第 3、5 条（超席）被跳过，doc2 的切片保留
        assertThat(output).extracting(r -> r.chunk().getDocId()).containsExactly(1L, 1L, 2L);
    }

    @Test
    @DisplayName("maxPerDoc=1：严格每文档一席")
    void maxOne_shouldKeepBestPerDocument() {
        List<SearchResult> inputs = List.of(
                result(1L, 0.9), result(2L, 0.8), result(1L, 0.7));

        List<SearchResult> output = new DocDiversityPostProcessor(1).process(inputs, context);

        assertThat(output).extracting(r -> r.chunk().getDocId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("maxPerDoc=0 或负数：直通不处理")
    void disabled_shouldPassThrough() {
        List<SearchResult> inputs = List.of(result(1L, 0.9), result(1L, 0.8), result(1L, 0.7));

        assertThat(new DocDiversityPostProcessor(0).process(inputs, context)).isSameAs(inputs);
        assertThat(new DocDiversityPostProcessor(-1).process(inputs, context)).isSameAs(inputs);
    }

    @Test
    @DisplayName("docId 缺失的切片不受席位约束")
    void nullDocId_shouldNotConsumeSeat() {
        List<SearchResult> inputs = List.of(result(null, 0.9), result(null, 0.8), result(2L, 0.7));

        List<SearchResult> output = new DocDiversityPostProcessor(1).process(inputs, context);

        assertThat(output).hasSize(3);
    }

    private SearchResult result(Long docId, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(System.nanoTime());
        chunk.setDocId(docId);
        return SearchResult.of("vector", chunk, score);
    }
}
