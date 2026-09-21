package com.nailinai.ragent.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private ChatCandidates chat = new ChatCandidates();
    private EmbeddingCandidates embedding = new EmbeddingCandidates();
    private Vision vision = new Vision();
    private Rerank rerank = new Rerank();
    private Retry retry = new Retry();
    private Timeout timeout = new Timeout();

    public ChatCandidates getChat() {
        return chat;
    }

    public void setChat(ChatCandidates chat) {
        this.chat = chat;
    }

    public EmbeddingCandidates getEmbedding() {
        return embedding;
    }

    public void setEmbedding(EmbeddingCandidates embedding) {
        this.embedding = embedding;
    }

    public Vision getVision() {
        return vision;
    }

    public void setVision(Vision vision) {
        this.vision = vision;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public void setTimeout(Timeout timeout) {
        this.timeout = timeout;
    }

    public static class ChatCandidates {
        private List<Candidate> candidates = new ArrayList<>();

        public List<Candidate> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<Candidate> candidates) {
            this.candidates = candidates;
        }
    }

    public static class EmbeddingCandidates {
        private List<Candidate> candidates = new ArrayList<>();

        public List<Candidate> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<Candidate> candidates) {
            this.candidates = candidates;
        }
    }

    /**
     * 视觉模型配置：用于把「图片型文档」转成可检索的文本描述。
     *
     * <p>未配置或 {@code enabled=false} 时，图片文档会退化为旧行为（解析出空文本、不入库切片），
     * 但会在日志中给出明确提示，而不是静默失败。
     */
    public static class Vision {
        private boolean enabled = false;
        private String provider = "vision";
        private String baseUrl;
        private String apiKey;
        private String model;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /**
     * 重排序模型配置（可选能力）：cross-encoder 精排。
     * 未配置或 enabled=false 时检索链路自动降级为启发式重排（RRF + 加权）。
     */
    public static class Rerank {
        private boolean enabled = false;
        private String provider = "siliconflow";
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey;
        private String model = "BAAI/bge-reranker-v2-m3";
        private long timeoutMs = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Candidate {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String model;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long backoffMs = 800;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }
    }

    public static class Timeout {
        private long streamTimeoutMs = 120_000;

        public long getStreamTimeoutMs() {
            return streamTimeoutMs;
        }

        public void setStreamTimeoutMs(long streamTimeoutMs) {
            this.streamTimeoutMs = streamTimeoutMs;
        }
    }
}
