package com.nailinai.ragent.eval;

import com.nailinai.ragent.chat.retrieve.DedupPostProcessor;
import com.nailinai.ragent.chat.retrieve.SearchChannel;
import com.nailinai.ragent.chat.retrieve.SearchResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.user.context.UserIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RagEvaluator 检索质量指标计算的单元测试。
 *
 * <p>注意：消融修复后，evaluate 的 rerank=true 分支不再复用 defaultRetriever，
 * 而是显式构造开启重排的检索器并真实调用通道，因此这里直接 stub 通道的
 * {@code search()}，让指标计算在真实检索装配上执行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagEvaluatorTest {

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private ChatClient chatClient;
    @Mock
    private SearchChannel dummyChannel;

    @BeforeEach
    void setUp() {
        // 评估链路走真实 RetrievalServiceImpl：ownerUserId 已 fail-closed，
        // 单测里模拟离线路径的显式绑定（对应生产中 RagEvaluationRunner 解析库 owner 后的绑定）
        UserIdHolder.set(1L);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1f));
        when(dummyChannel.name()).thenReturn("vector");
    }

    @AfterEach
    void tearDown() {
        UserIdHolder.clear();
    }

    @Test
    @DisplayName("返回 2 条且全部命中期望文档：recall=1.0 precision=1.0")
    void partialHit_shouldComputeMetrics() {
        when(dummyChannel.search(any())).thenReturn(List.of(
                result("aaa.txt"),
                result("task.txt")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题", List.of("aaa.txt", "task.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        EvaluationMetrics metrics = report.queries().get(0).metrics();
        assertThat(metrics.hitCount()).isEqualTo(2);
        assertThat(metrics.expectedCount()).isEqualTo(2);
        assertThat(metrics.retrievedCount()).isEqualTo(2);
        assertThat(metrics.recallAtK()).isEqualTo(1.0);
        assertThat(metrics.precisionAtK()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("检索结果不包含期望文档：recall=0")
    void noHit_shouldReturnZeroRecall() {
        when(dummyChannel.search(any())).thenReturn(List.of(
                result("other.txt")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题", List.of("aaa.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries().get(0).metrics().recallAtK()).isEqualTo(0.0);
        assertThat(report.queries().get(0).metrics().hitCount()).isZero();
    }

    @Test
    @DisplayName("期望文档为空：recall 定义为 1.0")
    void emptyExpected_shouldBePerfectRecall() {
        when(dummyChannel.search(any())).thenReturn(List.of(
                result("aaa.txt")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题", List.of(), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries().get(0).metrics().recallAtK()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("文档名包含匹配：UUID 前缀的真实文档名也能命中关键词")
    void documentNameContains_shouldMatch() {
        when(dummyChannel.search(any())).thenReturn(List.of(
                result("6bebfaa8-3d54-4870-8b02-2eeca0ea47f3-aaa.txt")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题", List.of("aaa.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries().get(0).metrics().hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("返回名是期望名的无关短子串：不再误判命中")
    void shortRetrievedName_shouldNotMatch() {
        // 旧实现双向 contains：期望 "long-report.txt" vs 返回名 "t" 会被误判命中。
        // 修复后只允许「期望名出现在返回名中」的方向。
        when(dummyChannel.search(any())).thenReturn(List.of(
                result("t")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题", List.of("long-report.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries().get(0).metrics().hitCount()).isZero();
    }

    @Test
    @DisplayName("多查询汇总：recall 取各条均值")
    void aggregate_shouldAverageAcrossQueries() {
        when(dummyChannel.search(any()))
                .thenReturn(List.of(result("aaa.txt")))
                .thenReturn(List.of(result("unrelated.txt")));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题1", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题2", List.of("task.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries()).hasSize(2);
        // 2 条查询：1 命中 + 0 命中，期望各 1 → 汇总 recall = 0.5
        assertThat(report.summary().hitCount()).isEqualTo(1);
        assertThat(report.summary().expectedCount()).isEqualTo(2);
        assertThat(report.summary().recallAtK()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("连续失败达到上限：抛 EvaluationAbortedException 中止整轮（防止系统性故障伪装成召回全零）")
    void consecutiveFailures_shouldAbortWholeRound() {
        // embedding 是单条查询路径上最靠前的基础设施依赖：它抛出才会绕过
        // MultiChannelRetriever 的通道隔离，进入 evaluate 的逐条容错
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("kb not found"));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));
        evaluator.setMaxConsecutiveFailures(2);

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题1", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题2", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题3", List.of("aaa.txt"), null)));

        assertThatThrownBy(() -> evaluator.evaluate(set, false, true))
                .isInstanceOf(EvaluationAbortedException.class)
                .hasMessageContaining("系统性故障");
    }

    @Test
    @DisplayName("零星失败未达连续上限：只跳过失败条，评估继续")
    void scatteredFailures_shouldNotAbort() {
        when(embeddingClient.embed(anyString()))
                .thenThrow(new RuntimeException("供应商抖动"))
                .thenReturn(List.of(0.1f))
                .thenThrow(new RuntimeException("供应商抖动"))
                .thenReturn(List.of(0.1f));
        RagEvaluator evaluator = new RagEvaluator(embeddingClient, chatClient, List.of(dummyChannel), List.of(new DedupPostProcessor()));
        evaluator.setMaxConsecutiveFailures(2);

        RagEvaluationSet set = new RagEvaluationSet(1L, 4, List.of(
                new RagEvaluationQuery("问题1", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题2", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题3", List.of("aaa.txt"), null),
                new RagEvaluationQuery("问题4", List.of("aaa.txt"), null)));
        EvaluationReport report = evaluator.evaluate(set, false, true);

        assertThat(report.queries()).hasSize(2);
    }

    private SearchResult result(String documentName) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(System.nanoTime());
        chunk.setDocumentName(documentName);
        return SearchResult.of("vector", chunk, 0.9);
    }
}
