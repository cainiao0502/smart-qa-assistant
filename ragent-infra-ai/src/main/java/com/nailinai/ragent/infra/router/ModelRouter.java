package com.nailinai.ragent.infra.router;

import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.LlmRequest;
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
    public ChatResponse chat(LlmRequest request) {
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
    public void streamChat(LlmRequest request, StreamCallback callback) {
        ensureChatCandidates();
        ErrorHolder lastError = new ErrorHolder();
        for (ChatClient candidate : chatCandidates) {
            String provider = candidate.name();
            if (!healthStore.allowCall(provider)) {
                log.info("Skip stream chat provider {} due to open circuit breaker", provider);
                continue;
            }
            final String currentProvider = provider;
            try {
                final boolean[] completed = {false};
                // 跟踪本候选是否已向底层 callback 转发过任何内容（正文或思考流）。
                // 一旦转发过，中途失败就不能再切换候选重流：否则候选 A 的半截输出
                // 会和候选 B 的完整输出拼在一起，用户收到重复/错乱内容。
                // 取舍：宁可这一轮流式请求失败（fail fast 交给上层处理），
                // 也不给用户重复拼接的内容。
                final boolean[] forwardedContent = {false};
                candidate.streamChat(request, new StreamCallback() {
                    @Override
                    public void onReasoning(String delta) {
                        forwardedContent[0] = true;
                        forwardToConsumer(() -> callback.onReasoning(delta));
                    }

                    @Override
                    public void onContent(String delta) {
                        forwardedContent[0] = true;
                        forwardToConsumer(() -> callback.onContent(delta));
                    }

                    @Override
                    public void onComplete() {
                        completed[0] = true;
                        healthStore.markSuccess(currentProvider);
                        forwardToConsumer(callback::onComplete);
                    }

                    @Override
                    public void onError(Throwable ex) {
                        completed[0] = true;
                        healthStore.markFailure(currentProvider);
                        if (forwardedContent[0]) {
                            // 已向用户推送过部分内容：不切换候选，直接把错误交给底层，
                            // 避免下一个候选从头重流造成「半截 A + 完整 B」的重复输出。
                            log.warn("Stream chat provider {} failed after emitting content, fail fast without failover",
                                    currentProvider, ex);
                            forwardToConsumer(() -> callback.onError(new IllegalStateException(
                                    "stream chat provider " + currentProvider
                                            + " failed after emitting partial content: " + ex.getMessage(), ex)));
                            return;
                        }
                        lastError.error = new IllegalStateException(
                                "stream chat provider " + currentProvider + " failed: " + ex.getMessage(), ex);
                        log.warn("Stream chat provider {} failed, trying next candidate", currentProvider, ex);
                    }
                });
                if (completed[0] && lastError.error != null) {
                    continue; // onError was called, try next candidate
                }
                return; // stream completed, in progress, or fail-fast after partial output
            } catch (ConsumerFailureException ex) {
                // 消费端回调失败（典型：SSE 客户端断连导致上层 sendEvent 抛异常）不是
                // 供应商故障：不计熔断统计（否则两次用户断连会把健康供应商误熔断 30 秒）、
                // 不切换候选、也无需再通知 onError（消费端已无法接收），直接终止本次流。
                log.warn("Stream chat consumer failed (client disconnected?), aborting without provider penalty", ex);
                return;
            } catch (RuntimeException ex) {
                healthStore.markFailure(provider);
                lastError.error = new IllegalStateException(
                        "stream chat provider " + provider + " threw before streaming: " + ex.getMessage(), ex);
                log.warn("Stream chat provider {} threw before streaming, trying next", provider, ex);
            }
        }
        callback.onError(lastError.error != null
                ? lastError.error
                : new IllegalStateException("no chat provider available for streaming"));
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

    /**
     * 把 delta/完成/错误事件转发给底层消费者。转发失败（典型：SSE 客户端断连导致
     * 上层抛异常）包装为 {@link ConsumerFailureException} 抛出，与供应商故障区分。
     */
    private void forwardToConsumer(Runnable forward) {
        try {
            forward.run();
        } catch (RuntimeException ex) {
            throw new ConsumerFailureException(ex);
        }
    }

    /** 消费端回调失败：不是供应商故障，不参与熔断统计与候选切换 */
    private static class ConsumerFailureException extends RuntimeException {
        ConsumerFailureException(Throwable cause) {
            super(cause);
        }
    }
    private static class ErrorHolder {
        volatile IllegalStateException error;
    }
}