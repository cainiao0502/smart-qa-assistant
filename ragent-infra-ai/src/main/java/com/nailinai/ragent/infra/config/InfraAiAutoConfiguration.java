package com.nailinai.ragent.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.OpenAICompatibleChatClient;
import com.nailinai.ragent.infra.embedding.CachingEmbeddingClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.infra.rerank.RerankClient;
import com.nailinai.ragent.infra.rerank.SiliconFlowRerankClient;

import com.nailinai.ragent.infra.embedding.OpenAICompatibleEmbeddingClient;
import com.nailinai.ragent.infra.router.ModelHealthStore;
import com.nailinai.ragent.infra.router.ModelRouter;
import com.nailinai.ragent.infra.vision.OpenAICompatibleVisionClient;
import com.nailinai.ragent.infra.vision.VisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class InfraAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InfraAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ModelHealthStore modelHealthStore() {
        return new ModelHealthStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(AiProperties properties, ObjectMapper objectMapper) {
        List<ChatClient> candidates = new ArrayList<>();
        for (AiProperties.Candidate candidate : properties.getChat().getCandidates()) {
            if (isBlank(candidate.getBaseUrl()) || isBlank(candidate.getApiKey())) {
                log.warn("Skip chat candidate {} due to missing base-url or api-key", candidate.getProvider());
                continue;
            }
            candidates.add(new OpenAICompatibleChatClient(
                    candidate.getProvider(),
                    candidate.getBaseUrl(),
                    candidate.getApiKey(),
                    candidate.getModel(),
                    properties.getRetry().getMaxAttempts(),
                    properties.getRetry().getBackoffMs(),
                    properties.getTimeout().getStreamTimeoutMs(),
                    objectMapper
            ));
            log.info("Registered chat candidate: provider={}, model={}", candidate.getProvider(), candidate.getModel());
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No valid chat candidate configured under ai.chat.candidates");
        }
        return new ModelRouter(candidates, List.of(), modelHealthStore());
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingClient embeddingClient(AiProperties properties, ObjectMapper objectMapper) {
        List<EmbeddingClient> candidates = new ArrayList<>();
        for (AiProperties.Candidate candidate : properties.getEmbedding().getCandidates()) {
            if (isBlank(candidate.getBaseUrl()) || isBlank(candidate.getApiKey())) {
                log.warn("Skip embedding candidate {} due to missing base-url or api-key", candidate.getProvider());
                continue;
            }
            candidates.add(new CachingEmbeddingClient(
                    new OpenAICompatibleEmbeddingClient(
                            candidate.getProvider(),
                            candidate.getBaseUrl(),
                            candidate.getApiKey(),
                            candidate.getModel(),
                            objectMapper
                    ), 256
            ));
            log.info("Registered embedding candidate: provider={}, model={}", candidate.getProvider(), candidate.getModel());
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No valid embedding candidate configured under ai.embedding.candidates");
        }
        return new ModelRouter(List.of(), candidates, modelHealthStore());
    }

    /**
     * 视觉模型客户端（可选能力）。
     *
     * <p>未配置时返回「不可用」实现，让调用方（图片文档解析）退化为纯文本行为，
     * 而不是在启动期直接失败——图片理解是增量特性，不该阻塞主链路启动。
     */
    @Bean
    @ConditionalOnMissingBean
    public VisionClient visionClient(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.Vision vision = properties.getVision();
        if (!vision.isEnabled()
                || isBlank(vision.getBaseUrl())
                || isBlank(vision.getApiKey())
                || isBlank(vision.getModel())) {
            log.info("Vision model is not configured; image documents will be indexed as empty text. "
                    + "Set ai.vision.* to enable image understanding.");
            return VisionClient.unavailable();
        }
        ChatClient visionChatClient = new OpenAICompatibleChatClient(
                isBlank(vision.getProvider()) ? "vision" : vision.getProvider(),
                vision.getBaseUrl(),
                vision.getApiKey(),
                vision.getModel(),
                properties.getRetry().getMaxAttempts(),
                properties.getRetry().getBackoffMs(),
                properties.getTimeout().getStreamTimeoutMs(),
                objectMapper
        );
        log.info("Registered vision model: provider={}, model={}", vision.getProvider(), vision.getModel());
        return new OpenAICompatibleVisionClient(visionChatClient);
    }

    /**
     * 重排序模型客户端（可选能力）。
     *
     * <p>未配置时返回「不可用」实现，RerankPostProcessor 检测到后自动退回
     * 启发式重排（RRF + 词法/语义加权），不阻塞检索主链路。
     */
    @Bean
    @ConditionalOnMissingBean
    public RerankClient rerankClient(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.Rerank rerank = properties.getRerank();
        if (!rerank.isEnabled() || isBlank(rerank.getBaseUrl()) || isBlank(rerank.getApiKey())) {
            log.info("Rerank model is not configured; retrieval falls back to heuristic rerank. "
                    + "Set ai.rerank.* to enable cross-encoder rerank.");
            return RerankClient.unavailable();
        }
        log.info("Registered rerank model: provider={}, model={}", rerank.getProvider(), rerank.getModel());
        return new SiliconFlowRerankClient(
                rerank.getBaseUrl(),
                rerank.getApiKey(),
                rerank.getModel(),
                rerank.getTimeoutMs(),
                objectMapper
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}