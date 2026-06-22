package com.nailinai.ragent.infra.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAICompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleEmbeddingClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String providerName;
    private final String embeddingModel;
    private final String apiKey;
    private final boolean miniMaxEmbeddingApi;

    public OpenAICompatibleEmbeddingClient(String providerName,
                                           String baseUrl,
                                           String apiKey,
                                           String embeddingModel,
                                           ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.providerName = providerName;
        this.embeddingModel = embeddingModel;
        this.apiKey = apiKey;
        this.miniMaxEmbeddingApi = baseUrl.contains("minimaxi.com") || embeddingModel.startsWith("embo-");
    }

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String responseBody = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(buildEmbeddingRequestBody(List.of(text), "query"))
                    .retrieve()
                    .body(String.class);
            return parseEmbedding(responseBody);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(buildProviderHttpError(ex), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("failed to call embedding API", ex);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        try {
            String responseBody = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(buildEmbeddingRequestBody(texts, "db"))
                    .retrieve()
                    .body(String.class);
            List<List<Float>> embeddings = parseEmbeddings(responseBody);
            if (embeddings.size() != texts.size()) {
                return texts.stream().map(this::embed).toList();
            }
            return embeddings;
        } catch (RestClientResponseException ex) {
            log.warn("embedding batch failed for provider {}, falling back to single embed", providerName, ex);
            return texts.stream().map(this::embed).toList();
        } catch (Exception ex) {
            log.warn("embedding batch failed for provider {}, falling back to single embed", providerName, ex);
            return texts.stream().map(this::embed).toList();
        }
    }

    private String buildProviderHttpError(RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        if (responseBody != null && !responseBody.isBlank()) {
            return "embedding API request failed with HTTP " + ex.getStatusCode().value() + ": " + responseBody;
        }
        return "embedding API request failed with HTTP " + ex.getStatusCode().value();
    }

    private List<Float> parseEmbedding(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = resolveEmbeddingArray(root, 0);
            return toFloatList(embeddingNode);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse embedding response", ex);
        }
    }

    private List<List<Float>> parseEmbeddings(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = resolveEmbeddingContainer(root);
            List<List<Float>> embeddings = new ArrayList<>();
            for (JsonNode item : dataNode) {
                embeddings.add(resolveBatchItem(item));
            }
            return embeddings;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse batch embedding response", ex);
        }
    }

    private Map<String, Object> buildEmbeddingRequestBody(List<String> texts, String miniMaxType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        if (miniMaxEmbeddingApi) {
            body.put("texts", texts);
            body.put("type", miniMaxType);
        } else if (texts.size() == 1) {
            body.put("input", texts.get(0));
        } else {
            body.put("input", texts);
        }
        return body;
    }

    private JsonNode resolveEmbeddingContainer(JsonNode root) {
        JsonNode vectorsNode = root.path("vectors");
        if (vectorsNode.isArray()) {
            return vectorsNode;
        }
        JsonNode dataNode = root.path("data");
        if (dataNode.isArray()) {
            return dataNode;
        }
        throwIfProviderReturnedError(root);
        throw new IllegalStateException("embedding array is missing in response");
    }

    private JsonNode resolveEmbeddingArray(JsonNode root, int index) {
        JsonNode container = resolveEmbeddingContainer(root);
        JsonNode node = container.get(index);
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("embedding item is missing in response");
        }
        if (node.isArray()) {
            return node;
        }
        JsonNode embeddingNode = node.path("embedding");
        if (embeddingNode.isArray()) {
            return embeddingNode;
        }
        throw new IllegalStateException("embedding field is missing or invalid");
    }

    private List<Float> resolveBatchItem(JsonNode item) {
        if (item.isArray()) {
            return toFloatList(item);
        }
        JsonNode embeddingNode = item.path("embedding");
        if (embeddingNode.isArray()) {
            return toFloatList(embeddingNode);
        }
        throw new IllegalStateException("embedding field is missing or invalid");
    }

    private void throwIfProviderReturnedError(JsonNode root) {
        JsonNode baseRespNode = root.path("base_resp");
        if (!baseRespNode.isObject()) {
            return;
        }
        String statusMessage = baseRespNode.path("status_msg").asText(null);
        int statusCode = baseRespNode.path("status_code").asInt(0);
        if (statusMessage != null && !statusMessage.isBlank()) {
            throw new IllegalStateException("embedding provider error (" + statusCode + "): " + statusMessage);
        }
    }

    private List<Float> toFloatList(JsonNode embeddingNode) {
        if (!embeddingNode.isArray()) {
            throw new IllegalStateException("embedding field is missing or invalid");
        }
        List<Float> result = new ArrayList<>(embeddingNode.size());
        for (JsonNode valueNode : embeddingNode) {
            result.add((float) valueNode.asDouble());
        }
        return result;
    }
}