package com.nailinai.ragent.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.OpenAICompatibleChatClient;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.infra.embedding.OpenAICompatibleEmbeddingClient;
import com.nailinai.ragent.infra.router.ModelHealthStore;
import com.nailinai.ragent.infra.router.ModelRouter;
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
            candidates.add(new OpenAICompatibleEmbeddingClient(
                    candidate.getProvider(),
                    candidate.getBaseUrl(),
                    candidate.getApiKey(),
                    candidate.getModel(),
                    objectMapper
            ));
            log.info("Registered embedding candidate: provider={}, model={}", candidate.getProvider(), candidate.getModel());
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No valid embedding candidate configured under ai.embedding.candidates");
        }
        return new ModelRouter(List.of(), candidates, modelHealthStore());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}