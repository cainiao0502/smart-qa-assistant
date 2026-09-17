package com.nailinai.ragent.infra.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple in-memory LRU cache decorator for EmbeddingClient.
 * Caches single embed() results to avoid redundant API calls for repeated queries.
 */
public class CachingEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbeddingClient.class);

    private final EmbeddingClient delegate;
    private final EmbeddingCache cache;

    public CachingEmbeddingClient(EmbeddingClient delegate, int maxSize) {
        this.delegate = delegate;
        this.cache = new EmbeddingCache(maxSize);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return delegate.embed(text);
        }

        List<Float> cached = cache.get(text);
        if (cached != null) {
            log.debug("Embedding cache hit for text length={}", text.length());
            return cached;
        }

        List<Float> result = delegate.embed(text);
        cache.put(text, result);
        return result;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return delegate.embedBatch(texts);
    }

    private static class EmbeddingCache {
        private final int maxSize;
        private final LinkedHashMap<String, List<Float>> map;

        EmbeddingCache(int maxSize) {
            this.maxSize = maxSize;
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Float>> eldest) {
                    return size() > maxSize;
                }
            };
        }

        synchronized List<Float> get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, List<Float> value) {
            map.put(key, value);
        }
    }
}
