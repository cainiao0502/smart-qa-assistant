package com.nailinai.ragent.chat.retrieve;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DedupPostProcessor implements SearchPostProcessor {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        Map<Long, SearchResult> deduped = new LinkedHashMap<>();
        for (SearchResult result : inputs) {
            Long chunkId = result.chunkId();
            if (chunkId == null) {
                continue;
            }
            SearchResult existing = deduped.get(chunkId);
            if (existing == null || result.rawScore() > existing.rawScore()) {
                deduped.put(chunkId, result);
            }
        }

        return deduped.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::rawScore).reversed())
                .toList();
    }
}