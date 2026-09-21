package com.nailinai.ragent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.chat.service.McpProcessManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class McpClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** 未知 content 结构的兜底截断长度，防止任意未知类型把模型上下文撑爆。 */
    static final int MAX_UNKNOWN_ITEM_CHARS = 800;
    /** 顶层兜底字段（structuredContent/data/output/result）的截断长度。 */
    static final int MAX_FALLBACK_CHARS = 2000;

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final McpProcessManager mcpProcessManager;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public McpClient(McpProperties properties, ObjectMapper objectMapper, McpProcessManager mcpProcessManager) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mcpProcessManager = mcpProcessManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean isConfigured(String serverId, McpProperties.ServerProperties server) {
        if (server == null) {
            return false;
        }
        if (server.isStdio()) {
            return server.getLaunchCommand() != null && !server.getLaunchCommand().isEmpty();
        }
        return StringUtils.hasText(server.getBaseUrl());
    }

    public List<McpToolDefinition> listTools(String serverId, McpProperties.ServerProperties server) {
        JsonNode result = invokeWithSession(serverId, server, "tools/list", Map.of());
        JsonNode toolsNode = result.path("tools");
        List<McpToolDefinition> definitions = new ArrayList<>();
        if (!toolsNode.isArray()) {
            return definitions;
        }

        for (JsonNode toolNode : toolsNode) {
            String remoteName = toolNode.path("name").asText("");
            if (!StringUtils.hasText(remoteName)) {
                continue;
            }
            String exposedName = toExposedName(server, remoteName);
            definitions.add(new McpToolDefinition(
                    serverId,
                    serverId,
                    exposedName,
                    remoteName,
                    toolNode.path("description").asText(""),
                    objectMapper.convertValue(toolNode.path("inputSchema"), Map.class),
                    parseAnnotations(toolNode.path("annotations"))
            ));
        }
        return definitions;
    }

    /**
     * 解析 MCP 规范的工具风险标注。
     *
     * <p>规范里 {@code readOnlyHint} 缺省为 false、{@code destructiveHint} 缺省为 true，
     * 因此这里<b>只把服务端显式声明的 true 当作 true</b>，其余一律走保守值——
     * 一个不表态的第三方工具不应该被当成安全的。</p>
     */
    private ToolAnnotations parseAnnotations(JsonNode annotationsNode) {
        if (annotationsNode == null || annotationsNode.isMissingNode() || annotationsNode.isNull()
                || !annotationsNode.isObject()) {
            return ToolAnnotations.UNKNOWN;
        }
        return new ToolAnnotations(
                true,
                annotationsNode.path("readOnlyHint").asBoolean(false),
                annotationsNode.path("destructiveHint").asBoolean(true),
                annotationsNode.path("openWorldHint").asBoolean(true)
        );
    }

    public McpCallResult callTool(String serverId,
                                  McpProperties.ServerProperties server,
                                  String remoteToolName,
                                  Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", remoteToolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);

        JsonNode result = invokeWithSession(serverId, server, "tools/call", params);
        boolean isError = result.path("isError").asBoolean(false);
        String supplementalContext = extractSupplementalContext(result);
        String summary = firstLine(supplementalContext);
        if (!StringUtils.hasText(summary)) {
            summary = isError ? "MCP tool returned an error" : "MCP tool call completed";
        }
        return new McpCallResult(isError, summary, supplementalContext, objectMapper.convertValue(result, Object.class));
    }

    public void resetSession(String serverId) {
        SessionState state = sessions.remove(serverId);
        if (state != null) {
            state.reset();
        }
    }

    private JsonNode invokeWithSession(String serverId,
                                       McpProperties.ServerProperties server,
                                       String method,
                                       Map<String, Object> params) {
        ensureConfigured(serverId, server);
        SessionState state = sessions.computeIfAbsent(serverId, key ->
                server.isSse() ? new SseSessionState() : new SessionState());
        synchronized (state) {
            if (!state.initialized) {
                initialize(serverId, server, state);
            }
            try {
                return invoke(serverId, server, state, method, params, true);
            } catch (BusinessException ex) {
                if (server.isHttpLike() && ex.getMessage() != null && ex.getMessage().contains("HTTP 404")) {
                    state.reset();
                    initialize(serverId, server, state);
                    return invoke(serverId, server, state, method, params, true);
                }
                throw ex;
            }
        }
    }

    private void initialize(String serverId, McpProperties.ServerProperties server, SessionState state) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
                "name", "miniagent-backend",
                "version", "0.0.1"
        ));

        JsonRpcEnvelope envelope = post(serverId, server, state, buildRequest("initialize", params, true), false);
        JsonNode result = envelope.result();
        state.protocolVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        state.sessionId = firstHeaderIgnoreCase(envelope.headers(), "Mcp-Session-Id");
        state.initialized = true;

        post(serverId, server, state, buildNotification("notifications/initialized", Map.of()), true);
    }

    private JsonNode invoke(String serverId,
                            McpProperties.ServerProperties server,
                            SessionState state,
                            String method,
                            Map<String, Object> params,
                            boolean includeProtocolHeader) {
        JsonRpcEnvelope envelope = post(serverId, server, state, buildRequest(method, params, true), includeProtocolHeader);
        return envelope.result();
    }

    private JsonRpcEnvelope post(String serverId,
                                 McpProperties.ServerProperties server,
                                 SessionState state,
                                 Map<String, Object> body,
                                 boolean includeProtocolHeader) {
        try {
            if (server.isStdio()) {
                return postViaStdio(serverId, server, body);
            }
            if (server.isSse()) {
                return postViaSse(serverId, server, state, body, includeProtocolHeader);
            }
            return postViaHttp(server, state, body, includeProtocolHeader);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to call MCP server: " + ex.getMessage());
        }
    }

    private JsonRpcEnvelope postViaStdio(String serverId,
                                         McpProperties.ServerProperties server,
                                         Map<String, Object> body) throws IOException {
        String payloadJson = objectMapper.writeValueAsString(body);
        Object requestId = body.get("id");
        if (requestId == null) {
            mcpProcessManager.sendStdioNotification(serverId, server, payloadJson);
            return new JsonRpcEnvelope(objectMapper.createObjectNode(), Map.of());
        }
        JsonNode result = mcpProcessManager.sendStdioRequest(serverId, server, String.valueOf(requestId), payloadJson,
                Math.max(1000, properties.getRequestTimeoutMs()));
        return new JsonRpcEnvelope(result, Map.of());
    }

    private JsonRpcEnvelope postViaHttp(McpProperties.ServerProperties server,
                                        SessionState state,
                                        Map<String, Object> body,
                                        boolean includeProtocolHeader) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.getBaseUrl()))
                .timeout(Duration.ofMillis(Math.max(1000, properties.getRequestTimeoutMs())))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

        if (includeProtocolHeader) {
            builder.header("MCP-Protocol-Version", state.protocolVersion == null ? PROTOCOL_VERSION : state.protocolVersion);
        }
        if (StringUtils.hasText(state.sessionId)) {
            builder.header("Mcp-Session-Id", state.sessionId);
        }
        if (StringUtils.hasText(server.getApiKey())) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + server.getApiKey());
        }
        if (server.getHeaders() != null) {
            server.getHeaders().forEach(builder::header);
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            String errorBody;
            try (InputStream bodyStream = response.body()) {
                errorBody = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP request failed with HTTP " + statusCode + ": " + errorBody);
        }

        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(MediaType.APPLICATION_JSON_VALUE);
        JsonNode payload;
        try (InputStream bodyStream = response.body()) {
            byte[] bodyBytes = bodyStream.readAllBytes();
            if (bodyBytes.length == 0) {
                payload = objectMapper.createObjectNode();
            } else if (contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                payload = parseSseResponse(bodyBytes);
            } else {
                payload = objectMapper.readTree(bodyBytes);
            }
        }

        if (payload == null || payload.isMissingNode()) {
            return new JsonRpcEnvelope(objectMapper.createObjectNode(), response.headers().map());
        }
        JsonNode errorNode = payload.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String errorMessage = errorNode.path("message").asText("unknown MCP error");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP error: " + errorMessage);
        }
        return new JsonRpcEnvelope(payload.path("result"), response.headers().map());
    }

    private JsonRpcEnvelope postViaSse(String serverId,
                                       McpProperties.ServerProperties server,
                                       SessionState state,
                                       Map<String, Object> body,
                                       boolean includeProtocolHeader) throws IOException, InterruptedException {
        SseSessionState sseState = (SseSessionState) state;
        synchronized (sseState) {
            if (!sseState.sseConnected) {
                connectSseStream(serverId, server, sseState);
            }
        }

        String postUrl = sseState.postEndpoint;
        if (!StringUtils.hasText(postUrl)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SSE endpoint not received from server: " + serverId);
        }

        Object requestId = body.get("id");
        boolean isNotification = requestId == null;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(postUrl))
                .timeout(Duration.ofMillis(Math.max(1000, properties.getRequestTimeoutMs())))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

        if (includeProtocolHeader) {
            builder.header("MCP-Protocol-Version", sseState.protocolVersion == null ? PROTOCOL_VERSION : sseState.protocolVersion);
        }
        if (StringUtils.hasText(sseState.sessionId)) {
            builder.header("Mcp-Session-Id", sseState.sessionId);
        }
        if (StringUtils.hasText(server.getApiKey())) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + server.getApiKey());
        }
        if (server.getHeaders() != null) {
            server.getHeaders().forEach(builder::header);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SSE POST failed with HTTP " + statusCode + ": " + response.body());
        }

        if (isNotification) {
            return new JsonRpcEnvelope(objectMapper.createObjectNode(), response.headers().map());
        }

        String sessionIdHeader = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionIdHeader != null) {
            sseState.sessionId = sessionIdHeader;
        }

        JsonNode pending = sseState.pollResponse(String.valueOf(requestId),
                Duration.ofMillis(Math.max(1000, properties.getRequestTimeoutMs())));
        if (pending == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SSE response timeout for request: " + requestId);
        }
        JsonNode errorNode = pending.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String errorMessage = errorNode.path("message").asText("unknown MCP error");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP error: " + errorMessage);
        }
        return new JsonRpcEnvelope(pending.path("result"), response.headers().map());
    }

    private void connectSseStream(String serverId,
                                  McpProperties.ServerProperties server,
                                  SseSessionState sseState) throws IOException, InterruptedException {
        String sseUrl = server.getBaseUrl();
        if (!sseUrl.endsWith("/sse")) {
            sseUrl = sseUrl.replaceAll("/+$", "") + "/sse";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .timeout(Duration.ofMillis(Math.max(1000, properties.getRequestTimeoutMs())))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream is = response.body()) {
                String errorBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "SSE connection failed with HTTP " + response.statusCode() + ": " + errorBody);
            }
        }

        String sessionIdHeader = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionIdHeader != null) {
            sseState.sessionId = sessionIdHeader;
        }

        // 持有当前流的引用：reset() 关闭它，reader 线程 readLine 会抛 IOException 退出，
        // 否则 404 重连路径每次都泄漏一条 HTTP 连接 + 一个守护线程
        InputStream stream = response.body();
        sseState.sseStream = stream;

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder dataBuilder = new StringBuilder();
                String eventType = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (dataBuilder.length() > 0) {
                            dataBuilder.append('\n');
                        }
                        dataBuilder.append(line.substring(5).trim());
                    } else if (line.isBlank() && dataBuilder.length() > 0) {
                        String data = dataBuilder.toString();
                        dataBuilder.setLength(0);
                        if ("endpoint".equals(eventType)) {
                            sseState.postEndpoint = resolveSseEndpoint(server.getBaseUrl(), data.trim());
                            sseState.sseConnected = true;
                        } else {
                            JsonNode payload = readJsonQuietly(data);
                            if (payload != null && payload.has("id")) {
                                sseState.offerResponse(payload.path("id").asText(), payload);
                            }
                        }
                        eventType = "";
                    }
                }
            } catch (Exception ignored) {
            } finally {
                // 只有当前连接才允许把 sseConnected 打回 false：reset()/重连关闭旧流后，
                // 旧 reader 线程从这里退出，不能误伤新连接的已连接状态
                if (sseState.sseStream == stream) {
                    sseState.sseConnected = false;
                }
            }
        }, "sse-reader-" + serverId);
        readerThread.setDaemon(true);
        readerThread.start();

        long deadline = System.currentTimeMillis() + Math.max(1000, properties.getRequestTimeoutMs());
        while (!sseState.sseConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!sseState.sseConnected) {
            // 握手超时：此刻流还没有交付给任何有效会话，直接关掉，避免再泄漏一条连接 + 一个线程
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            sseState.sseStream = null;
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "SSE endpoint event not received from server: " + serverId);
        }
    }

    private String resolveSseEndpoint(String baseUrl, String endpointPath) {
        if (endpointPath.startsWith("http://") || endpointPath.startsWith("https://")) {
            return endpointPath;
        }
        String base = baseUrl.replaceAll("/sse/?$", "").replaceAll("/+$", "");
        return base + (endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
    }

    private JsonNode parseSseResponse(byte[] bytes) throws IOException {
        JsonNode lastPayload = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuilder = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (dataBuilder.length() > 0) {
                        JsonNode parsed = readJsonQuietly(dataBuilder.toString());
                        if (parsed != null) {
                            lastPayload = parsed;
                        }
                        dataBuilder.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (dataBuilder.length() > 0) {
                        dataBuilder.append('\n');
                    }
                    dataBuilder.append(line.substring(5).trim());
                }
            }
            if (dataBuilder.length() > 0) {
                JsonNode parsed = readJsonQuietly(dataBuilder.toString());
                if (parsed != null) {
                    lastPayload = parsed;
                }
            }
        }
        return lastPayload == null ? objectMapper.createObjectNode() : lastPayload;
    }

    private JsonNode readJsonQuietly(String raw) throws IOException {
        if (!StringUtils.hasText(raw) || "[DONE]".equals(raw.trim())) {
            return null;
        }
        return objectMapper.readTree(raw);
    }

    private Map<String, Object> buildRequest(String method, Map<String, Object> params, boolean includeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (includeId) {
            body.put("id", UUID.randomUUID().toString());
        }
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        return body;
    }

    private Map<String, Object> buildNotification(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        return body;
    }

    /**
     * 从 tools/call 结果中提取可回灌给模型的文本。
     *
     * <p>MCP 规范里 {@code content} 是多类型数组（text / image / audio / resource_link / resource），
     * 未知类型也允许出现。<b>image / audio 的 payload 是 base64</b>，直接序列化会把几十 KB 的
     * 编码灌进模型上下文——这里一律替换成占位说明；未知结构也做长度截断兜底。</p>
     */
    String extractSupplementalContext(JsonNode result) {
        StringBuilder builder = new StringBuilder();
        collectContentItems(builder, result.path("content"));
        if (builder.isEmpty() && result.has("structuredContent")) {
            appendBlock(builder, truncateChars(result.path("structuredContent").toPrettyString(), MAX_FALLBACK_CHARS));
        }
        if (builder.isEmpty() && result.has("data")) {
            appendBlock(builder, truncateNode(result.path("data")));
        }
        if (builder.isEmpty() && result.has("output")) {
            appendBlock(builder, truncateNode(result.path("output")));
        }
        if (builder.isEmpty() && result.has("result")) {
            appendBlock(builder, truncateNode(result.path("result")));
        }
        if (builder.isEmpty() && result.isObject()) {
            appendBlock(builder, truncateChars(result.toPrettyString(), MAX_FALLBACK_CHARS));
        }
        return builder.toString().trim();
    }

    private void collectContentItems(StringBuilder builder, JsonNode contentNode) {
        if (!contentNode.isArray()) {
            return;
        }
        for (JsonNode item : contentNode) {
            String type = item.path("type").asText("");
            switch (type) {
                case "text" -> appendBlock(builder, item.path("text").asText(""));
                case "image" -> appendBlock(builder, binaryPlaceholder("图片", item));
                case "audio" -> appendBlock(builder, binaryPlaceholder("音频", item));
                case "resource_link" -> appendBlock(builder, resourceLinkText(item));
                case "resource" -> appendBlock(builder, embeddedResourceText(item));
                default -> {
                    // 兼容不写 type 的 server：带 text 字段就按文本收，其余走截断兜底
                    if (item.has("text") && item.path("text").isTextual()) {
                        appendBlock(builder, item.path("text").asText(""));
                    } else if (item.has("structuredContent")) {
                        appendBlock(builder, truncateChars(item.path("structuredContent").toPrettyString(), MAX_UNKNOWN_ITEM_CHARS));
                    } else if (item.isObject() && item.size() > 0) {
                        appendBlock(builder, truncateChars(item.toPrettyString(), MAX_UNKNOWN_ITEM_CHARS));
                    }
                }
            }
        }
    }

    private String binaryPlaceholder(String label, JsonNode item) {
        String base64 = item.path("data").asText("");
        int approxBytes = base64.length() * 3 / 4;
        return "[%s已省略] mimeType=%s, 约 %d KB — 二进制内容不进入模型上下文，请需要时通过其他工具获取"
                .formatted(label, item.path("mimeType").asText("unknown"), Math.max(1, approxBytes / 1024));
    }

    private String resourceLinkText(JsonNode item) {
        String uri = item.path("uri").asText("");
        String name = item.path("name").asText("");
        return "[资源链接] " + uri + (StringUtils.hasText(name) ? " (" + name + ")" : "");
    }

    private String embeddedResourceText(JsonNode item) {
        JsonNode resource = item.path("resource");
        if (resource.isMissingNode() || resource.isNull() || !resource.isObject()) {
            return truncateChars(item.toPrettyString(), MAX_UNKNOWN_ITEM_CHARS);
        }
        if (resource.has("text") && resource.path("text").isTextual()) {
            return resource.path("text").asText("");
        }
        if (resource.has("blob")) {
            return binaryPlaceholder("嵌入资源", resource);
        }
        return truncateChars(resource.toPrettyString(), MAX_UNKNOWN_ITEM_CHARS);
    }

    private String truncateChars(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }

    private String truncateNode(JsonNode node) {
        String text = node == null || node.isMissingNode() || node.isNull() ? "" : node.toPrettyString();
        return truncateChars(text, MAX_FALLBACK_CHARS);
    }

    private void appendBlock(StringBuilder builder, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(value.trim());
    }

    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int index = text.indexOf('\n');
        return index >= 0 ? text.substring(0, index).trim() : text.trim();
    }

    /**
     * 生成暴露给模型的工具名。
     *
     * <p>OpenAI function calling 规范要求 function name 匹配 {@code ^[a-zA-Z0-9_-]{1,64}$}——
     * 带 {@code .} 的名字会让严格校验的上游<b>拒收整个请求</b>（HTTP 400，且错误文案误导为
     * "不支持工具调用"）。分隔符用 {@code -}，并对最终名字整体净化。</p>
     */
    String toExposedName(McpProperties.ServerProperties server, String remoteName) {
        String exposed = StringUtils.hasText(server.getToolNamePrefix())
                ? server.getToolNamePrefix().trim() + "-" + remoteName
                : remoteName;
        return sanitizeToolName(exposed);
    }

    /** 按 OpenAI function name 规范净化工具名：非法字符折叠为 {@code -}，超长截断到 64。 */
    static String sanitizeToolName(String name) {
        if (!StringUtils.hasText(name)) {
            return name;
        }
        String cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "-").replaceAll("-{2,}", "-");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private void ensureConfigured(String serverId, McpProperties.ServerProperties server) {
        if (!isConfigured(serverId, server)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP server is not configured: " + serverId);
        }
    }

    private String firstHeaderIgnoreCase(Map<String, List<String>> headers, String target) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(target) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static class SessionState {
        boolean initialized;
        String sessionId;
        String protocolVersion = PROTOCOL_VERSION;

        void reset() {
            this.initialized = false;
            this.sessionId = null;
            this.protocolVersion = PROTOCOL_VERSION;
        }
    }

    private static final class SseSessionState extends SessionState {
        private volatile boolean sseConnected;
        private volatile String postEndpoint;
        /** 当前 SSE 响应流；reset() 必须先 close 它，让持有它的守护 reader 线程退出。 */
        private volatile InputStream sseStream;
        private final ConcurrentMap<String, java.util.concurrent.CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();

        void offerResponse(String id, JsonNode payload) {
            java.util.concurrent.CompletableFuture<JsonNode> future = pendingResponses.remove(id);
            if (future != null) {
                future.complete(payload);
            }
        }

        JsonNode pollResponse(String id, Duration timeout) {
            java.util.concurrent.CompletableFuture<JsonNode> future = new java.util.concurrent.CompletableFuture<>();
            pendingResponses.put(id, future);
            try {
                return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                // 恢复中断标志：否则上层线程池（如虚拟线程执行器）的中断信号在这里丢失
                pendingResponses.remove(id);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception ex) {
                pendingResponses.remove(id);
                return null;
            }
        }

        @Override
        void reset() {
            super.reset();
            // 关旧流 → 旧 reader 线程在 readLine 上抛 IOException 退出（其 catch 会静默吞掉，
            // 不会误报），finally 里 sseStream==stream 校验保证不会误伤新连接的 sseConnected。
            InputStream old = this.sseStream;
            this.sseStream = null;
            if (old != null) {
                try {
                    old.close();
                } catch (IOException ignored) {
                }
            }
            this.sseConnected = false;
            this.postEndpoint = null;
            pendingResponses.clear();
        }
    }

    private record JsonRpcEnvelope(JsonNode result, Map<String, List<String>> headers) {
    }
}
