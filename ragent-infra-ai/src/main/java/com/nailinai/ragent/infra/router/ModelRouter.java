package com.nailinai.ragent.infra.router;

import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatRequest;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.StreamCallback;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.List;

public class ModelRouter implements ChatClient, EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final List<ChatClient> chatCandidates;
    private final List<EmbeddingClient> embeddingCandidates;
    private final ModelHealthStore healthStore;

    public ModelRouter(List<ChatClient> chatCandidates,
                       List<EmbeddingClient> embeddingCandidates,
                       ModelHealthStore healthStore) {
        Assert.notNull(chatCandidates, "chat candidates must not be null");
        Assert.notNull(embeddingCandidates, "embedding candidates must not be null");
        Assert.isTrue(!chatCandidates.isEmpty() || !embeddingCandidates.isEmpty(),
                "at least one of chat or embedding candidates must be non-empty");
        this.chatCandidates = chatCandidates;
        this.embeddingCandidates = embeddingCandidates;
        this.healthStore = healthStore;
    }

    @Override
    public String name() {
        return "model-router";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ensureChatCandidates();
        IllegalStateException lastException = null;
        for (ChatClient candidate : chatCandidates) {
            String provider = candidate.name();
            if (!healthStore.allowCall(provider)) {
                log.info("Skip chat provider {} due to open circuit breaker", provider);
                continue;
            }
            try {
                ChatResponse response = candidate.chat(request);
                healthStore.markSuccess(provider);
                return response;
            } catch (RuntimeException ex) {
                healthStore.markFailure(provider);
                lastException = new IllegalStateException(
                        "chat provider " + provider + " failed: " + ex.getMessage(), ex);
                log.warn("Chat provider {} failed, trying next candidate", provider, ex);
            }
        }
        throw lastException == null
                ? new IllegalStateException("no chat provider available")
                : lastException;
    }

    @Override
    public void streamChat(ChatRequest request, StreamCallback callback) {
        ensureChatCandidates();
        for (ChatClient candidate : chatCandidates) {
            String provider = candidate.name();
            if (!healthStore.allowCall(provider)) {
                log.info("Skip stream chat provider {} due to open circuit breaker", provider);
                continue;
            }
            final String currentProvider = provider;
            try {
                candidate.streamChat(request, new StreamCallback() {
                    @Override
                    public void onReasoning(String delta) {
                        callback.onReasoning(delta);
                    }

                    @Override
                    public void onContent(String delta) {
                        callback.onContent(delta);
                    }

                    @Override
                    public void onComplete() {
                        healthStore.markSuccess(currentProvider);
                        callback.onComplete();
                    }

                    @Override
                    public void onError(Throwable ex) {
                        healthStore.markFailure(currentProvider);
                        log.warn("Stream chat provider {} failed, trying next candidate", currentProvider, ex);
                    }
                });
                return;
            } catch (RuntimeException ex) {
                healthStore.markFailure(provider);
                log.warn("Stream chat provider {} threw before streaming, trying next", provider, ex);
            }
        }
        callback.onError(new IllegalStateException("no chat provider available for streaming"));
    }

    @Override
    public List<Float> embed(String text) {
        ensureEmbeddingCandidates();
        IllegalStateException lastException = null;
        for (EmbeddingClient candidate : embeddingCandidates) {
            String provider = candidate.name();
            if (!healthStore.allowCall(provider)) {
                log.info("Skip embedding provider {} due to open circuit breaker", provider);
                continue;
            }
            try {
                List<Float> result = candidate.embed(text);
                healthStore.markSuccess(provider);
                return result;
            } catch (RuntimeException ex) {
                healthStore.markFailure(provider);
                lastException = new IllegalStateException(
                        "embedding provider " + provider + " failed: " + ex.getMessage(), ex);
                log.warn("Embedding provider {} failed, trying next candidate", provider, ex);
            }
        }
        throw lastException == null
                ? new IllegalStateException("no embedding provider available")
                : lastException;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        ensureEmbeddingCandidates();
        IllegalStateException lastException = null;
        for (EmbeddingClient candidate : embeddingCandidates) {
            String provider = candidate.name();
            if (!healthStore.allowCall(provider)) {
                log.info("Skip embedding batch provider {} due to open circuit breaker", provider);
                continue;
            }
            try {
                List<List<Float>> result = candidate.embedBatch(texts);
                healthStore.markSuccess(provider);
                return result;
            } catch (RuntimeException ex) {
                healthStore.markFailure(provider);
                lastException = new IllegalStateException(
                        "embedding batch provider " + provider + " failed: " + ex.getMessage(), ex);
                log.warn("Embedding batch provider {} failed, trying next candidate", provider, ex);
            }
        }
        throw lastException == null
                ? new IllegalStateException("no embedding provider available")
                : lastException;
    }

    public ModelHealthStore healthStore() {
        return healthStore;
    }

    private void ensureChatCandidates() {
        if (chatCandidates == null || chatCandidates.isEmpty()) {
            throw new IllegalStateException("no chat candidate configured");
        }
    }

    private void ensureEmbeddingCandidates() {
        if (embeddingCandidates == null || embeddingCandidates.isEmpty()) {
            throw new IllegalStateException("no embedding candidate configured");
        }
    }
}