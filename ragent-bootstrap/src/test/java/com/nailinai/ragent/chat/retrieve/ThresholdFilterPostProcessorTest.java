package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阈值过滤 + 截断的单元测试（S0-② / B1 / C2）。
 *
 * <p>核心回归：关键词独有命中在启发式重排下 rerankScore 上限只剩 lexical-weight
 * （0.25），用 cosine 量纲标定的 0.6 阈值一刀切会把关键词通道整体绞杀（S0-② 第三环）。
 * 新规则：有向量证据的命中走融合分阈值，关键词独有命中走关键词通道自己的门槛。</p>
 *
 * <p><b>B1 教训（本类曾把缺陷写成规格）：</b>原有一版用例断言「双通道命中即便向量侧
 * cosine 0.55 &lt; 0.6 也应通过，理由是以主通道 rawScore=1.0 判定」——那正是 B1 本身：
 * 向量证据分支回退 {@code rawScore()}（主通道分），而关键词归一 ts_rank 第一名恒 1.0，
 * 会赢过 cosine 成为主通道，于是向量阈值对「关键词排第一 + 向量共同命中」的切片失效。
 * 现在的规则是<b>按证据量纲取证</b>：向量分支必须用向量侧分数，不得借用关键词分。
 * 教训与 S0-①（入库截断被写成规格）同源：<b>把观测到的行为写成断言之前，先问它是不是缺陷。</b></p>
 */
class ThresholdFilterPostProcessorTest {

    @Test
    @DisplayName("关键词独有命中：rerankScore 0.25 < 通用阈值 0.6，但按关键词门槛（0）通过")
    void keywordOnlyHit_shouldNotBeStrangledByFusedThreshold() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setRerankScore(0.25); // 启发式重排下的天花板：lexical-weight × 1.0
        SearchResult keywordOnly = SearchResult.of("keyword", chunk, 0.8);

        List<SearchResult> output = processor.process(List.of(keywordOnly), context);

        assertThat(output).hasSize(1);
    }

    @Test
    @DisplayName("有向量证据的命中：融合分低于通用阈值被过滤")
    void vectorHit_belowFusedThreshold_shouldBeFiltered() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(2L);
        chunk.setRerankScore(0.5);
        SearchResult vectorHit = SearchResult.of("vector", chunk, 0.7);

        assertThat(processor.process(List.of(vectorHit), context)).isEmpty();
    }

    @Test
    @DisplayName("cross-encoder 生效：向量证据命中改用 CE 量纲阈值")
    void crossEncoderActive_shouldUseCrossEncoderScale() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.45);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);
        context.addFlag(SearchContext.FLAG_CROSS_ENCODER);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(3L);
        chunk.setRerankScore(0.5); // CE 量纲下 0.45 即可通过，cosine 量纲的 0.6 会误杀
        SearchResult vectorHit = SearchResult.of("vector", chunk, 0.55);

        List<SearchResult> output = processor.process(List.of(vectorHit), context);

        assertThat(output).hasSize(1);
    }

    @Test
    @DisplayName("cross-encoder 阈值未标定（<=0）：沿用通用阈值")
    void crossEncoderUncalibrated_shouldFallBackToGenericThreshold() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);
        context.addFlag(SearchContext.FLAG_CROSS_ENCODER);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(4L);
        chunk.setRerankScore(0.5);
        SearchResult vectorHit = SearchResult.of("vector", chunk, 0.55);

        assertThat(processor.process(List.of(vectorHit), context)).isEmpty();
    }

    @Test
    @DisplayName("关键词独有命中受关键词门槛约束：rawScore 低于门槛被过滤")
    void keywordOnlyHit_belowKeywordMinScore_shouldBeFiltered() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.9, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(5L);
        chunk.setRerankScore(0.25);
        SearchResult keywordOnly = SearchResult.of("keyword", chunk, 0.8);

        assertThat(processor.process(List.of(keywordOnly), context)).isEmpty();
    }

    @Test
    @DisplayName("重排关闭：向量命中按 cosine 比阈值，关键词命中按自身门槛")
    void rerankDisabled_shouldUsePerChannelScales() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.9, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk vectorChunk = new DocumentChunk();
        vectorChunk.setId(6L);
        SearchResult vectorHit = SearchResult.of("vector", vectorChunk, 0.7); // cosine 0.7 ≥ 0.6

        DocumentChunk keywordChunk = new DocumentChunk();
        keywordChunk.setId(7L);
        SearchResult keywordOnly = SearchResult.of("keyword", keywordChunk, 0.5); // 归一 ts_rank 0.5 < 0.9 门槛

        LinkedHashMap<String, Double> both = new LinkedHashMap<>();
        both.put("keyword", 1.0); // 归一 ts_rank 第一名恒 1.0 → 去重时成为【主通道】
        both.put("vector", 0.35); // 向量侧 cosine 0.35 < 0.6 → 必须被过滤
        SearchResult doubleHit = SearchResult.merged(keywordChunk, both);

        List<SearchResult> output = processor.process(List.of(vectorHit, keywordOnly, doubleHit), context);

        assertThat(output).extracting(SearchResult::rawScore).containsExactly(0.7);
    }

    @Test
    @DisplayName("B1：双通道命中不得借用主通道的关键词分通过【向量】阈值")
    void doubleHit_belowVectorThreshold_mustNotBorrowPrimaryKeywordScore() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(10L);
        // 无 rerankScore：覆盖两条触发路径——rerank.enabled=false（重排不在链上），
        // 或 cross-encoder 启用但调用失败（RerankPostProcessor 提前返回、不写分）
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("keyword", 1.0);
        scores.put("vector", 0.30);
        SearchResult doubleHit = SearchResult.merged(chunk, scores);

        // 旧实现回退 rawScore()=1.0（主通道 = 关键词）→ 以 1.0 通过 0.6 的向量阈值，
        // 向量阈值对「关键词排第一 + 向量共同命中」的切片形同虚设（B1）。
        assertThat(processor.process(List.of(doubleHit), context)).isEmpty();
    }

    @Test
    @DisplayName("B1 对照：向量侧 cosine 达标时双通道命中正常通过（不因修复而误杀）")
    void doubleHit_aboveVectorThreshold_shouldPass() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(11L);
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("keyword", 1.0);
        scores.put("vector", 0.65); // cosine 0.65 ≥ 0.6
        SearchResult doubleHit = SearchResult.merged(chunk, scores);

        assertThat(processor.process(List.of(doubleHit), context)).hasSize(1);
    }

    @Test
    @DisplayName("B1：cross-encoder 生效时按 CE 分量纲判，不受主通道关键词分影响")
    void doubleHit_withCrossEncoder_shouldUseCrossEncoderScore() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.45);
        SearchContext context = SearchContext.of("q", "q", 4, 0.6);
        context.addFlag(SearchContext.FLAG_CROSS_ENCODER);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(12L);
        chunk.setRerankScore(0.50); // CE 量纲 0.45 门槛 → 通过
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("keyword", 1.0);
        scores.put("vector", 0.20); // 向量侧远低于通用阈值，但 CE 生效时不影响判定
        SearchResult doubleHit = SearchResult.merged(chunk, scores);

        assertThat(processor.process(List.of(doubleHit), context)).hasSize(1);
    }

    @Test
    @DisplayName("C2 先筛后取前 K：被阈值否掉的席位由更靠后的候选回填")
    void truncationShouldHappenAfterFiltering() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 2, 0.6); // topK = 2

        List<SearchResult> candidates = List.of(
                vectorResult(1L, 0.90), // 过阈值
                vectorResult(2L, 0.20), // 不过阈值 —— 旧实现里它先占掉一个 top-2 席位
                vectorResult(3L, 0.80), // 过阈值，应由它补位
                vectorResult(4L, 0.70)); // 过阈值，但超出 topK 被截断

        List<SearchResult> output = processor.process(candidates, context);

        // 旧顺序（先 limit 后 filter）会得到 [1] 一条；新顺序（先 filter 后 limit）得到 [1, 3]
        assertThat(output).extracting(r -> r.chunk().getId()).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("C2 兜底：截断在链末位，因此总结果不超过 top-k")
    void outputNeverExceedsTopK() {
        ThresholdFilterPostProcessor processor = new ThresholdFilterPostProcessor(0.0, 0.0);
        SearchContext context = SearchContext.of("q", "q", 3, 0.0);

        List<SearchResult> candidates = List.of(
                vectorResult(1L, 0.9), vectorResult(2L, 0.8),
                vectorResult(3L, 0.7), vectorResult(4L, 0.6), vectorResult(5L, 0.5));

        assertThat(processor.process(candidates, context)).hasSize(3);
    }

    private SearchResult vectorResult(Long chunkId, double cosine) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(chunkId);
        return SearchResult.of("vector", chunk, cosine);
    }
}
