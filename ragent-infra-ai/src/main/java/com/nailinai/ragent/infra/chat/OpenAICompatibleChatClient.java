package com.nailinai.ragent.infra.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

public class OpenAICompatibleChatClient extends AbstractOpenAIStyleChatClient {

    private final String providerName;

    public OpenAICompatibleChatClient(String providerName,
                                      String baseUrl,
                                      String apiKey,
                                      String model,
                                      int maxAttempts,
                                      long retryBackoffMs,
                                      ObjectMapper objectMapper) {
        super(baseUrl, apiKey, model, maxAttempts, retryBackoffMs, objectMapper);
        this.providerName = providerName;
    }

    @Override
    public String name() {
        return providerName;
    }
}