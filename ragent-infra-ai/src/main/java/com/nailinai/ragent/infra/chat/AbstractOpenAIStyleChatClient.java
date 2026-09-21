package com.nailinai.ragent.infra.chat;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    protected final long streamTimeoutMs;

    protected AbstractOpenAIStyleChatClient(String baseUrl,
                                            String apiKey,
                                            String model,
                                            int maxAttempts,
                                            long retryBackoffMs,
                                            long streamTimeoutMs,
                                            ObjectMapper objectMapper) {
        HttpClient.Builder httpBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        configureProxy(httpBuilder, baseUrl);
        this.httpClient = httpBuilder.build();
        this.streamTimeoutMs = Math.max(10_000, streamTimeoutMs);
        // 非流式 chat() 走 RestClient，默认没有任何读超时——上游挂起时会无限阻塞
        // 调用线程（agent 循环的 planner 超时只能"放弃等待"，无法真正断开连接）。
        // 这里复用 streamTimeoutMs 作为读超时，与超时守卫形成双层边界。
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(this.streamTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    @Override
    public ChatResponse chat(LlmRequest request) {
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

                ChatResponse parsed = parseResponse(responseBody);
                // 原生工具调用轮次下 content 为空属正常，必须先于「空内容」判断
                if (parsed.hasToolCalls() || StringUtils.hasText(parsed.content())) {
                    return parsed;
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
    public void streamChat(LlmRequest request, StreamCallback callback) {
        IllegalStateException lastException = null;
        // 跨 attempt 跟踪是否已向底层 callback 推送过任何事件（正文或思考流）。
        // 只统计 onContent 会漏掉「先推了 reasoning 再失败」的流：此时整单重试会把
        // 相同的思考内容再推一遍。这里用包装回调把 onReasoning/onContent 都计入。
        java.util.concurrent.atomic.AtomicBoolean anyEmitted = new java.util.concurrent.atomic.AtomicBoolean(false);
        StreamCallback trackedCallback = new StreamCallback() {
            @Override
            public void onReasoning(String delta) {
                anyEmitted.set(true);
                callback.onReasoning(delta);
            }

            @Override
            public void onContent(String delta) {
                anyEmitted.set(true);
                callback.onContent(delta);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(Throwable ex) {
                callback.onError(ex);
            }
        };
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 本 attempt 是否拿到过正文内容（空白流允许重试，但 anyEmitted 已拦截重复推送）
            boolean receivedContent = false;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(buildChatCompletionUrl()))
                        .timeout(Duration.ofMillis(streamTimeoutMs))
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
                try (InputStream body = response.body()) {
                    receivedContent = consumeStream(body, trackedCallback);
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
                // 已向用户推送过部分 delta 时禁止重试：重试会从头发起请求并把完整
                // 输出追加在已推送内容之后，造成重复。只有完全没输出过的失败才允许重试。
                if (anyEmitted.get()) {
                    callback.onError(new IllegalStateException(
                            "failed to read chat stream response after emitting partial content", ex));
                    return;
                }
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

    /**
     * 组装请求体。
     *
     * <p>改造点：messages 直接透传（不再硬编码单条 user 消息）；
     * 当请求携带工具声明时下发 {@code tools} 与 {@code tool_choice}。
     */
    protected Map<String, Object> buildChatRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages().isEmpty()
                ? List.of(Map.of("role", "user", "content", request.prompt()))
                : request.messages());
        body.put("temperature", request.temperature() != null ? request.temperature() : 0.2);
        body.put("stream", stream);
        // 说明：此前此处固定下发 MiniMax 私有参数 reasoning_split=true；
        // 2026-09 起 MiniMax 对未知字段返回 400 UNKNOWN_FIELD，且 DeepSeek / SiliconFlow
        // 等本就原生返回 reasoning_content，无需该参数 —— 故不再下发。
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.hasTools()) {
            body.put("tools", request.tools().stream().map(ToolSpec::toOpenAiFormat).toList());
            body.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
        }
        return body;
    }

    protected String buildChatCompletionUrl() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/chat/completions";
    }

    /**
     * 解析完整响应（含原生工具调用）。
     *
     * <p>与旧版 {@code parseContent} 的关键差异：content 缺失不再直接抛错，
     * 因为 {@code finish_reason=tool_calls} 时 content 本就为空。
     */
    protected ChatResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode firstChoice = root.path("choices").path(0);
            JsonNode message = firstChoice.path("message");

            String finishReason = firstChoice.path("finish_reason").asText("");

            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode rawToolCalls = message.path("tool_calls");
            if (rawToolCalls.isArray()) {
                for (JsonNode rawCall : rawToolCalls) {
                    JsonNode function = rawCall.path("function");
                    String toolName = function.path("name").asText("");
                    if (!StringUtils.hasText(toolName)) {
                        continue;
                    }
                    String callId = rawCall.path("id").asText("");
                    String rawArguments = function.path("arguments").asText("");
                    toolCalls.add(new ToolCall(
                            StringUtils.hasText(callId)
                                    ? callId
                                    : "call_" + UUID.randomUUID().toString().replace("-", ""),
                            toolName,
                            parseArguments(rawArguments),
                            rawArguments
                    ));
                }
            }

            JsonNode contentNode = message.path("content");
            String content = (contentNode.isMissingNode() || contentNode.isNull())
                    ? null
                    : sanitizeContent(contentNode.asText());

            return new ChatResponse(content, name(), toolCalls, finishReason, parseUsage(root.path("usage")));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse chat completion response", ex);
        }
    }

    /**
     * 解析供应商上报的 token 用量。
     *
     * <p>字段名兼容两派：OpenAI 风格 {@code prompt_tokens/completion_tokens}，
     * Anthropic 风格 {@code input_tokens/output_tokens}。供应商没返回时记为 0，
     * 不做本地估算——宁可缺失，也不要伪造一个看起来合理的数字。
     *
     * <p>同时解析<b>缓存命中</b>与<b>思考</b> token：agent 循环里 system prompt 与工具 schema
     * 每次重发，但重复前缀通常命中 prompt 缓存并按折扣价计费（实测命中率约 90%），
     * 只看 prompt_tokens 会把成本高估一个量级。字段位置兼容 DeepSeek 的
     * {@code prompt_cache_hit_tokens} 与 OpenAI 的 {@code prompt_tokens_details.cached_tokens}。
     */
    protected TokenUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return TokenUsage.EMPTY;
        }
        int inputTokens = pickFirstPositive(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("input_tokens").asInt(0));
        int outputTokens = pickFirstPositive(
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("output_tokens").asInt(0));
        int cachedTokens = pickFirstPositive(
                usageNode.path("prompt_cache_hit_tokens").asInt(0),
                usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0));
        int reasoningTokens = usageNode.path("completion_tokens_details").path("reasoning_tokens").asInt(0);

        return TokenUsage.of(inputTokens, outputTokens, cachedTokens, reasoningTokens);
    }

    private int pickFirstPositive(int primary, int fallback) {
        return primary > 0 ? primary : Math.max(0, fallback);
    }

    /** 解析工具调用参数（JSON 字符串 → Map）；解析失败降级为空参数并记录原始文本 */
    protected Map<String, Object> parseArguments(String rawArguments) {
        if (!StringUtils.hasText(rawArguments)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawArguments);
            if (!node.isObject()) {
                return Map.of();
            }
            Map<String, Object> parsed = objectMapper.convertValue(node, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse tool call arguments, falling back to empty: {}", summarizeBody(rawArguments), ex);
            return Map.of();
        }
    }

    /** 仅取文本内容（保留以兼容既有子类与调用点） */
    protected String parseContent(String responseBody) {
        return parseResponse(responseBody).content();
    }

    protected boolean consumeStream(InputStream inputStream, StreamCallback callback) throws IOException {
        boolean receivedContent = false;
        StringBuilder aggregatedContent = new StringBuilder();
        StringBuilder aggregatedReasoning = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            // HttpRequest.timeout 只约束到「收到响应头」为止；readLine 会无限阻塞
            // 等待行数据，上游挂起（建立连接后不再发数据）时连接与线程被无限占用。
            // 改为 ready() 轮询读取：无数据时 sleep 50ms 并检查空闲 deadline
            // （空闲上限复用 streamTimeoutMs），空闲超时抛 InterruptedIOException 走
            // 既有 IOException 语义。[DONE] 处理与 UTF-8 读取行为保持不变。
            String line;
            long idleDeadline = System.currentTimeMillis() + streamTimeoutMs;
            while ((line = readLineWithIdleTimeout(reader, idleDeadline)) != null) {
                // 收到完整一行即视为流活跃，重置空闲计时
                idleDeadline = System.currentTimeMillis() + streamTimeoutMs;
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

    /**
     * 带空闲超时的行读取，语义与 {@link BufferedReader#readLine()} 一致：
     * 返回一行内容（不含行终止符），流结束时返回 null（含最后无换行符的残余行）。
     *
     * <p>ready() 为 false 时 sleep 50ms 并检查空闲 deadline，超时抛出
     * {@link InterruptedIOException}；线程被中断时恢复中断标志并同样抛出
     * {@link InterruptedIOException} 正确退出。ready() 为 true 时逐字符读取——
     * 此时单字符 read 不会阻塞，整行拼接期间也不会被不发数据的上游无限挂起。
     */
    private String readLineWithIdleTimeout(BufferedReader reader, long idleDeadline) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("chat stream read interrupted");
            }
            if (System.currentTimeMillis() > idleDeadline) {
                throw new InterruptedIOException("chat stream idle timeout after " + streamTimeoutMs + " ms");
            }
            if (reader.ready()) {
                int ch = reader.read();
                if (ch < 0) {
                    // EOF：与 readLine 一致，无换行结尾的残余行也要返回
                    return line.length() == 0 ? null : line.toString();
                }
                if (ch == '\n') {
                    int last = line.length() - 1;
                    if (last >= 0 && line.charAt(last) == '\r') {
                        line.setLength(last);
                    }
                    return line.toString();
                }
                line.append((char) ch);
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("chat stream read interrupted");
                }
            }
        }
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
            log.warn("Skip unparsable stream payload: {}", summarizeBody(payload), ex);
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

    /**
     * 计算增量文本。
     *
     * <p>注意：本方法同时兼容「累积型」与「增量型」两种 provider 行为，
     * 但仅适用于文本 delta。流式工具调用（tool_calls）的参数是分片拼接语义，
     * 不可复用本方法。
     */
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

        long delay = retryBackoffMs * (1L << (attempt - 1));
        double jitter = 0.8 + Math.random() * 0.4;
        delay = (long) (delay * jitter);
        long maxDelay = 30_000L;
        delay = Math.min(delay, maxDelay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("llm retry interrupted", ex);
        }
    }

    private static void configureProxy(HttpClient.Builder builder, String targetUrl) {
        boolean isHttps = targetUrl != null && targetUrl.startsWith("https");
        String host = null;
        int port = -1;

        // 1. Try Java system properties (set via -Dhttps.proxyHost, -Dhttps.proxyPort)
        String sysPropPrefix = isHttps ? "https" : "http";
        String sysHost = System.getProperty(sysPropPrefix + ".proxyHost");
        String sysPort = System.getProperty(sysPropPrefix + ".proxyPort");
        if (sysHost != null && !sysHost.isBlank()) {
            host = sysHost;
            try { port = Integer.parseInt(sysPort); } catch (Exception ignored) { }
        }

        // 2. Try environment variables (HTTPS_PROXY / HTTP_PROXY / https_proxy / http_proxy)
        if (host == null || port <= 0) {
            String envProxy = isHttps
                    ? System.getenv("HTTPS_PROXY")
                    : System.getenv("HTTP_PROXY");
            if (envProxy == null || envProxy.isBlank()) {
                envProxy = isHttps
                        ? System.getenv("https_proxy")
                        : System.getenv("http_proxy");
            }
            if (envProxy != null && !envProxy.isBlank()) {
                try {
                    URI proxyUri = URI.create(envProxy);
                    String envHost = proxyUri.getHost();
                    int envPort = proxyUri.getPort();
                    if (envHost != null) {
                        host = envHost;
                        port = envPort;
                    }
                } catch (Exception ex) {
                    log.warn("Failed to parse proxy from env: {}", envProxy, ex);
                }
            }
        }

        if (host != null && port > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            log.info("Using proxy {}:{} for LLM requests to {}", host, port, targetUrl);
        }
    }
}
