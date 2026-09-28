package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多通道合并去重的单元测试（S0-②）。
 *
 * <p>旧实现按 rawScore 择一保留通道身份——而关键词通道归一 ts_rank 第一名恒为 1.0，
 * 几乎总能赢过 cosine，双通道共同命中的向量语义证据被系统性丢弃。新实现合并全部
 * 通道身份，并按「命中通道数 → 通道内排名」排序（跨通道不可比的 rawScore 不再直接定序）。</p>
 */
class DedupPostProcessorTest {

    private final DedupPostProcessor processor = new DedupPostProcessor();
    private final SearchContext context = SearchContext.of("q", "q", 4, 0.0);

    @Test
    @DisplayName("双通道共同命中：合并为一条结果，两个通道的分数都保留")
    void doubleHit_shouldMergeChannelIdentities() {
        SearchResult vectorHit = SearchResult.of("vector", chunk(100L, "docA", 0.7), 0.7);
        SearchResult keywordHit = SearchResult.of("keyword", chunk(100L, "docA", 1.0), 1.0);

        List<SearchResult> output = processor.process(List.of(vectorHit, keywordHit), context);

        assertThat(output).hasSize(1);
        SearchResult merged = output.get(0);
        assertThat(merged.channelHitCount()).isEqualTo(2);
        assertThat(merged.scoreFrom("vector")).isEqualTo(0.7);
        assertThat(merged.scoreFrom("keyword")).isEqualTo(1.0);
        // 主通道 = 归一分最高的命中（与旧「保留最高分」的语义一致）
        assertThat(merged.channel()).isEqualTo("keyword");
        assertThat(merged.rawScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("排序：多通道共同命中优先于单通道命中（命中通道数降序）")
    void ordering_shouldPreferMultiChannelHits() {
        // chunk 100 被两个通道命中；101/102 只有向量命中且 cosine 更高
        List<SearchResult> input = List.of(
                SearchResult.of("vector", chunk(101L, "docB", 0.9), 0.9),
                SearchResult.of("keyword", chunk(100L, "docA", 1.0), 1.0),
                SearchResult.of("vector", chunk(100L, "docA", 0.7), 0.7),
                SearchResult.of("vector", chunk(102L, "docC", 0.6), 0.6));

        List<SearchResult> output = processor.process(input, context);

        assertThat(output).extracting(r -> r.chunk().getDocumentName()).containsExactly("docA", "docB", "docC");
    }

    @Test
    @DisplayName("同通道重复提交（防御）：取该通道最高分")
    void sameChannelDuplicate_shouldKeepMaxScore() {
        List<SearchResult> output = processor.process(List.of(
                SearchResult.of("vector", chunk(1L, "docA", 0.5), 0.5),
                SearchResult.of("vector", chunk(1L, "docA", 0.8), 0.8)), context);

        assertThat(output).hasSize(1);
        assertThat(output.get(0).scoreFrom("vector")).isEqualTo(0.8);
    }

    private DocumentChunk chunk(long id, String docName, double score) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentName(docName);
        c.setScore(score);
        return c;
    }
}
