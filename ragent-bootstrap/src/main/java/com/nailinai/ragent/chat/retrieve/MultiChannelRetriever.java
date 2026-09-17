package com.nailinai.ragent.chat.retrieve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MultiChannelRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRetriever.class);

    private final List<SearchChannel> channels;
    private final List<SearchPostProcessor> postProcessors;

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
        List<SearchResult> aggregated = new ArrayList<>();
        for (SearchChannel channel : channels) {
            try {
                List<SearchResult> results = channel.search(request);
                if (results != null && !results.isEmpty()) {
                    aggregated.addAll(results);
                    log.debug("channel {} returned {} results for kbId={}",
                            channel.name(), results.size(), request.kbId());
                }
            } catch (RuntimeException ex) {
                log.warn("channel {} failed for kbId={}: {}",
                        channel.name(), request.kbId(), ex.getMessage());
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
}