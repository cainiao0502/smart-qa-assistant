package com.nailinai.ragent.infra.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");
    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIStyleChatClient.class);

    protected final RestClient restClient;
    protected final HttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final String baseUrl;
    protected final String apiKey;
    protected final String model;
    protected final int maxAttempts;
    protected final long retryBackoffMs;

    protected AbstractOpenAIStyleChatClient(String baseUrl,
                                            String apiKey,
                                            String model,
                                            int maxAttempts,
                                            long retryBackoffMs,
                                            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        IllegalStateException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String responseBody = restClient.post()
                        .uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(buildChatRequestBody(request, false))
                        .retrieve()
                        .body(String.class);
                String answer = parseContent(responseBody);
                if (StringUtils.hasText(answer)) {
                    return new ChatResponse(answer, name());
                }
                lastException = new IllegalStateException("chat response content is blank");
                log.warn("LLM returned blank content on attempt {}/{}, provider={}, model={}",
                        attempt, maxAttempts, name(), model);
                if (attempt >= maxAttempts) {
                    throw lastException;
                }
                sleepBeforeRetry(attempt);
            } catch (RestClientResponseException ex) {
                lastException = new IllegalStateException(buildHttpErrorMessage(ex), ex);
                log.warn("LLM HTTP error on attempt {}/{}, provider={}, model={}, status={}, responseBody={}",
                        attempt, maxAttempts, name(), model, ex.getStatusCode().value(), summarizeBody(ex.getResponseBodyAsString()));
                if (!shouldRetry(ex) || attempt >= maxAttempts) {
                    throw lastException;
                }
                sleepBeforeRetry(attempt);
            } catch (RestClientException ex) {
                lastException = new IllegalStateException("failed to call chat completion API: " + ex.getMessage(), ex);
                log.warn("LLM transport error on attempt {}/{}, provider={}, model={}, message={}",
                        attempt, maxAttempts, name(), model, ex.getMessage());
                if (attempt >= maxAttempts) {
                    throw lastException;
                }
                sleepBeforeRetry(attempt);
            }
        }

        throw lastException == null
                ? new IllegalStateException("failed to get chat completion response")
                : lastException;
    }

    @Override
    public void streamChat(ChatRequest request, StreamCallback callback) {
        IllegalStateException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(buildChatCompletionUrl()))
                        .timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(buildChatRequestBody(request, true)),
                                StandardCharsets.UTF_8
                        ))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody;
                    try (InputStream body = response.body()) {
                        errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    lastException = new IllegalStateException("failed to call chat completion API: HTTP " + response.statusCode());
                    log.warn("LLM stream HTTP error on attempt {}/{}, provider={}, model={}, status={}, responseBody={}",
                            attempt, maxAttempts, name(), model, response.statusCode(), summarizeBody(errorBody));
                    if (attempt >= maxAttempts || !shouldRetryStatus(response.statusCode())) {
                        callback.onError(lastException);
                        return;
                    }
                    sleepBeforeRetry(attempt);
                    continue;
                }

                boolean receivedContent;
                try (InputStream body = response.body()) {
                    receivedContent = consumeStream(body, callback);
                }
                if (receivedContent) {
                    callback.onComplete();
                    return;
                }

                lastException = new IllegalStateException("chat stream response content is blank");
                log.warn("LLM stream returned blank content on attempt {}/{}, provider={}, model={}",
                        attempt, maxAttempts, name(), model);
                if (attempt >= maxAttempts) {
                    callback.onError(lastException);
                    return;
                }
                sleepBeforeRetry(attempt);
            } catch (IOException ex) {
                lastException = new IllegalStateException("failed to read chat stream response", ex);
                log.warn("LLM stream IO error on attempt {}/{}, provider={}, model={}, message={}",
                        attempt, maxAttempts, name(), model, ex.getMessage());
                if (attempt >= maxAttempts) {
                    callback.onError(lastException);
                    return;
                }
                sleepBeforeRetry(attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                callback.onError(new IllegalStateException("chat stream interrupted", ex));
                return;
            }
        }

        callback.onError(lastException == null
                ? new IllegalStateException("failed to get chat stream response")
                : lastException);
    }

    protected Map<String, Object> buildChatRequestBody(ChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "user", "content", request.prompt())
        ));
        body.put("temperature", request.temperature() != null ? request.temperature() : 0.2);
        body.put("stream", stream);
        body.put("reasoning_split", true);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        return body;
    }

    protected String buildChatCompletionUrl() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/chat/completions";
    }

    protected String parseContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new IllegalStateException("chat response content is missing");
            }
            return sanitizeContent(contentNode.asText());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse chat completion response", ex);
        }
    }

    protected boolean consumeStream(InputStream inputStream, StreamCallback callback) throws IOException {
        boolean receivedContent = false;
        StringBuilder aggregatedContent = new StringBuilder();
        StringBuilder aggregatedReasoning = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }

                String payload = line.substring(5).trim();
                if (!StringUtils.hasText(payload)) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }

                StreamDelta streamDelta = parseStreamContent(payload, aggregatedReasoning, aggregatedContent);
                if (StringUtils.hasText(streamDelta.reasoning())) {
                    callback.onReasoning(streamDelta.reasoning());
                }
                if (StringUtils.hasText(streamDelta.content())) {
                    receivedContent = true;
                    callback.onContent(streamDelta.content());
                }
            }
        }
        return receivedContent;
    }

    protected StreamDelta parseStreamContent(String payload, StringBuilder aggregatedReasoning, StringBuilder aggregatedContent) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode firstChoice = root.path("choices").path(0);
            JsonNode deltaContent = firstChoice.path("delta").path("content");
            JsonNode messageContent = firstChoice.path("message").path("content");
            String reasoning = extractReasoning(firstChoice);

            String content = null;
            if (!deltaContent.isMissingNode() && !deltaContent.isNull()) {
                content = deltaContent.asText();
            } else if (!messageContent.isMissingNode() && !messageContent.isNull()) {
                content = messageContent.asText();
            }

            String reasoningDelta = computeDelta(reasoning, aggregatedReasoning);
            String contentDelta = null;
            if (StringUtils.hasText(content)) {
                String current = sanitizeContent(content);
                if (StringUtils.hasText(current)) {
                    contentDelta = computeDelta(current, aggregatedContent);
                }
            }

            return new StreamDelta(reasoningDelta, contentDelta);
        } catch (Exception ex) {
            log.debug("Skip unparsable stream payload: {}", summarizeBody(payload), ex);
            return new StreamDelta(null, null);
        }
    }

    protected String extractReasoning(JsonNode firstChoice) {
        JsonNode reasoningContent = firstChoice.path("delta").path("reasoning_content");
        if (!reasoningContent.isMissingNode() && !reasoningContent.isNull()) {
            return sanitizeContent(reasoningContent.asText());
        }

        JsonNode reasoningDetails = firstChoice.path("delta").path("reasoning_details");
        if (reasoningDetails.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode detail : reasoningDetails) {
                JsonNode textNode = detail.path("text");
                if (!textNode.isMissingNode() && !textNode.isNull()) {
                    builder.append(textNode.asText());
                }
            }
            String text = sanitizeContent(builder.toString());
            return StringUtils.hasText(text) ? text : null;
        }
        return null;
    }

    protected String computeDelta(String current, StringBuilder aggregated) {
        if (!StringUtils.hasText(current)) {
            return null;
        }
        String accumulated = aggregated.toString();
        if (current.startsWith(accumulated)) {
            String delta = current.substring(accumulated.length());
            aggregated.setLength(0);
            aggregated.append(current);
            return delta;
        }
        aggregated.append(current);
        return current;
    }

    protected record StreamDelta(String reasoning, String content) {
    }

    protected String sanitizeContent(String content) {
        String cleaned = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        return cleaned.strip();
    }

    protected boolean shouldRetry(RestClientResponseException ex) {
        return shouldRetryStatus(ex.getStatusCode().value());
    }

    protected boolean shouldRetryStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    protected String buildHttpErrorMessage(RestClientResponseException ex) {
        if (ex.getStatusCode().value() == 401) {
            return "LLM API auth failed for provider " + name() + ", check API key";
        }
        return "failed to call chat completion API: HTTP " + ex.getStatusCode().value();
    }

    protected String summarizeBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "<empty>";
        }

        String normalized = body.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }

    protected void sleepBeforeRetry(int attempt) {
        if (retryBackoffMs <= 0) {
            return;
        }

        long delay = retryBackoffMs * attempt;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("llm retry interrupted", ex);
        }
    }
}