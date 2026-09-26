package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

/**
 * MultiChannelRetriever 混合检索链路的单元测试。
 *
 * <p>覆盖：多通道结果聚合、单通道故障隔离、后处理器按 order 排序执行、
 * 后处理器产出空结果时短路返回。
 */
class MultiChannelRetrieverTest {

    @Test
    @DisplayName("多通道结果正确聚合后交给后处理器")
    void shouldAggregateAcrossChannels() {
        SearchChannel keyword = channel("keyword", List.of(
                result("keyword", 1L, 0.9),
                result("keyword", 2L, 0.8)));
        SearchChannel vector = channel("vector", List.of(
                result("vector", 3L, 0.85)));

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);
        when(processor.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(keyword, vector), List.of(processor));

        SearchRequest request = SearchRequest.of(1L, "问题", null, 4, null, null, null, null);
        List<SearchResult> results = retriever.retrieve(request, SearchContext.of("问题", "问题", 4, 0.4));

        assertThat(results).hasSize(3);
        assertThat(results).extracting(SearchResult::channel).containsExactlyInAnyOrder("keyword", "keyword", "vector");
    }

    @Test
    @DisplayName("单个通道抛异常不影响其他通道结果")
    void channelFailure_shouldBeIsolated() {
        SearchChannel broken = mock(SearchChannel.class);
        when(broken.name()).thenReturn("broken");
        when(broken.search(any())).thenThrow(new RuntimeException("channel down"));

        SearchChannel healthy = channel("keyword", List.of(result("keyword", 1L, 0.9)));

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);
        when(processor.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(broken, healthy), List.of(processor));

        List<SearchResult> results = retriever.retrieve(
                SearchRequest.of(1L, "问题", null, 4, null, null, null, null),
                SearchContext.of("问题", "问题", 4, 0.4));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).channel()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("后处理器按 order 升序执行")
    void postProcessors_shouldRunInOrder() {
        SearchChannel channel = channel("keyword", List.of(result("keyword", 1L, 0.9)));

        SearchPostProcessor first = mock(SearchPostProcessor.class);
        when(first.order()).thenReturn(2);
        when(first.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        SearchPostProcessor second = mock(SearchPostProcessor.class);
        when(second.order()).thenReturn(1);
        when(second.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 故意乱序传入，验证按 order 排序后执行
        MultiChannelRetriever retriever = new MultiChannelRetriever(
                List.of(channel), List.of(first, second));

        retriever.retrieve(
                SearchRequest.of(1L, "问题", null, 4, null, null, null, null),
                SearchContext.of("问题", "问题", 4, 0.4));

        InOrder inOrder = inOrder(second, first);
        inOrder.verify(second).process(anyList(), any());
        inOrder.verify(first).process(anyList(), any());
    }

    @Test
    @DisplayName("后处理器产出空结果：检索短路返回空列表")
    void emptyAfterPostProcessor_shouldReturnEmpty() {
        SearchChannel channel = channel("keyword", List.of(result("keyword", 1L, 0.9)));

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);
        when(processor.process(anyList(), any())).thenReturn(List.of());

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(channel), List.of(processor));

        List<SearchResult> results = retriever.retrieve(
                SearchRequest.of(1L, "问题", null, 4, null, null, null, null),
                SearchContext.of("问题", "问题", 4, 0.4));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("所有通道均无结果：返回空列表且后处理器不执行")
    void noChannelResults_shouldReturnEmpty() {
        SearchChannel empty = channel("keyword", List.of());

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(empty), List.of(processor));

        List<SearchResult> results = retriever.retrieve(
                SearchRequest.of(1L, "问题", null, 4, null, null, null, null),
                SearchContext.of("问题", "问题", 4, 0.4));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("传入状态收集器时记录每个通道的参与/降级状态")
    void statusSink_shouldRecordChannelParticipation() {
        SearchChannel broken = mock(SearchChannel.class);
        when(broken.name()).thenReturn("broken");
        when(broken.search(any())).thenThrow(new RuntimeException("channel down"));

        SearchChannel healthy = channel("keyword", List.of(result("keyword", 1L, 0.9)));

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);
        when(processor.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(broken, healthy), List.of(processor));

        List<ChannelStatus> statusSink = new java.util.ArrayList<>();
        retriever.retrieve(
                SearchRequest.of(1L, "问题", null, 4, null, null, null, null),
                SearchContext.of("问题", "问题", 4, 0.4),
                statusSink);

        assertThat(statusSink).extracting(ChannelStatus::channel, ChannelStatus::degraded)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("broken", true),
                        org.assertj.core.groups.Tuple.tuple("keyword", false));
        assertThat(statusSink.stream().filter(ChannelStatus::degraded).findFirst().orElseThrow().error())
                .contains("channel down");
    }

    @Test
    @DisplayName("通道恢复后状态回到 ok，连续失败计数清零")
    void channelRecovery_shouldResetDegradedState() {
        SearchChannel flaky = mock(SearchChannel.class);
        when(flaky.name()).thenReturn("keyword");
        when(flaky.search(any())).thenThrow(new RuntimeException("down"))
                .thenThrow(new RuntimeException("down"))
                .thenReturn(List.of(result("keyword", 1L, 0.9)));

        SearchPostProcessor processor = mock(SearchPostProcessor.class);
        when(processor.order()).thenReturn(1);
        when(processor.process(anyList(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultiChannelRetriever retriever = new MultiChannelRetriever(List.of(flaky), List.of(processor));
        SearchRequest request = SearchRequest.of(1L, "问题", null, 4, null, null, null, null);
        SearchContext context = SearchContext.of("问题", "问题", 4, 0.4);

        List<ChannelStatus> firstRun = new java.util.ArrayList<>();
        retriever.retrieve(request, context, firstRun);
        List<ChannelStatus> secondRun = new java.util.ArrayList<>();
        retriever.retrieve(request, context, secondRun);
        List<ChannelStatus> thirdRun = new java.util.ArrayList<>();
        retriever.retrieve(request, context, thirdRun);

        assertThat(firstRun).allMatch(ChannelStatus::degraded);
        assertThat(secondRun).allMatch(ChannelStatus::degraded);
        assertThat(thirdRun).allMatch(status -> !status.degraded());
    }

    private SearchChannel channel(String name, List<SearchResult> results) {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.name()).thenReturn(name);
        when(channel.search(any())).thenReturn(results);
        return channel;
    }

    private SearchResult result(String channel, long chunkId, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(chunkId);
        return SearchResult.of(channel, chunk, score);
    }
}
