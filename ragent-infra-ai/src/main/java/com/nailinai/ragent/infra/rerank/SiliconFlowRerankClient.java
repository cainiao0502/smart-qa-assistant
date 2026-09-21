package com.nailinai.ragent.infra.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow 重排序客户端（兼容 Jina/Cohere 风格的 {@code POST /rerank} 协议）。
 *
 * <p>典型模型：{@code BAAI/bge-reranker-v2-m3}（relevance_score 经 sigmoid 归一到 [0,1]）。
 * 请求/响应形态：
 * <pre>
 * POST {base-url}/rerank
 * {"model": "...", "query": "...", "documents": ["...", "..."], "top_n": 10}
 * → {"results": [{"index": 0, "relevance_score": 0.98}, ...]}
 * </pre>
 * results 可能乱序返回，按 index 映射回入参下标。
 */
public class SiliconFlowRerankClient implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowRerankClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutMs;

    public SiliconFlowRerankClient(String baseUrl,
                                   String apiKey,
                                   String model,
                                   long timeoutMs,
                                   ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = Math.max(1000, timeoutMs);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(this.timeoutMs));
        this.restClient = RestClient.builder().baseUrl(this.baseUrl).requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String name() {
        return "siliconflow-rerank:" + model;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        if (topN > 0) {
            body.put("top_n", topN);
        }

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri("/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("rerank API failed with HTTP " + ex.getStatusCode().value()
                    + ": " + summarize(ex.getResponseBodyAsString()), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to call rerank API: " + ex.getMessage(), ex);
        }

        return parseResults(responseBody, documents.size());
    }

    private List<RerankResult> parseResults(String responseBody, int documentCount) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("rerank response missing results array");
            }
            List<RerankResult> parsed = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= documentCount || Double.isNaN(score)) {
                    log.warn("skip invalid rerank result item: index={}, score={}", index, score);
                    continue;
                }
                parsed.add(new RerankResult(index, score));
            }
            return parsed;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse rerank response", ex);
        }
    }

    private String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
