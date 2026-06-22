package com.nailinai.ragent.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private ChatCandidates chat = new ChatCandidates();
    private EmbeddingCandidates embedding = new EmbeddingCandidates();
    private Retry retry = new Retry();

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

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
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
}