package com.nailinai.ragent.chat.retrieve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component
public class MultiChannelRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRetriever.class);

    /**
     * 同一通道连续失败达到该次数后，日志从 warn 升级为 error：
     * 通道抛异常会被隔离（其余通道继续），但连续失败意味着检索在无声退化成
     * 「剩余通道的组合」——慢看得见，悄悄失效看不见，必须提高可见度。
     */
    private static final int ERROR_ESCALATION_THRESHOLD = 3;

    private final List<SearchChannel> channels;
    private final List<SearchPostProcessor> postProcessors;
    /** 跨请求累计的各通道连续失败次数：成功一次即清零 */
    private final ConcurrentMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public MultiChannelRetriever(List<SearchChannel> channels,
                                 List<SearchPostProcessor> postProcessors) {
        this.channels = channels;
        this.postProcessors = postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchPostProcessor::order))
                .collect(Collectors.toUnmodifiableList());
        log.info("MultiChannelRetriever initialized: channels={}, postProcessors={}",
                channels.stream().map(SearchChannel::name).toList(),
                this.postProcessors.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    public List<SearchResult> retrieve(SearchRequest request, SearchContext context) {
        return retrieve(request, context, null);
    }

    /**
     * 执行多通道检索。
     *
     * @param statusSink 可选的通道状态收集器；传入时每个通道的参与/降级状态都会记录，
     *                   调用方（RetrievalService）据此在响应中暴露「本轮哪些通道实际参与」
     */
    public List<SearchResult> retrieve(SearchRequest request, SearchContext context,
                                       List<ChannelStatus> statusSink) {
        List<SearchResult> aggregated = new ArrayList<>();
        for (SearchChannel channel : channels) {
            String channelName = channel.name();
            try {
                List<SearchResult> results = channel.search(request);
                if (results != null && !results.isEmpty()) {
                    aggregated.addAll(results);
                    log.debug("channel {} returned {} results for kbId={}",
                            channelName, results.size(), request.kbId());
                }
                consecutiveFailures.remove(channelName);
                reportStatus(statusSink, ChannelStatus.ok(channelName));
            } catch (RuntimeException ex) {
                int failures = consecutiveFailures.merge(channelName, 1, Integer::sum);
                if (failures >= ERROR_ESCALATION_THRESHOLD) {
                    log.error("channel {} failed {} consecutive times for kbId={} — "
                                    + "retrieval is silently degraded to the remaining channels: {}",
                            channelName, failures, request.kbId(), ex.getMessage());
                } else {
                    log.warn("channel {} failed for kbId={}: {}",
                            channelName, request.kbId(), ex.getMessage());
                }
                reportStatus(statusSink, ChannelStatus.degraded(channelName, ex.getMessage()));
            }
        }

        List<SearchResult> processed = aggregated;
        for (SearchPostProcessor processor : postProcessors) {
            processed = processor.process(processed, context);
            if (processed == null || processed.isEmpty()) {
                return List.of();
            }
        }
        return processed;
    }

    private void reportStatus(List<ChannelStatus> statusSink, ChannelStatus status) {
        if (statusSink != null) {
            statusSink.add(status);
        }
    }
}