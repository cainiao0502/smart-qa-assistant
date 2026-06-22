package com.nailinai.ragent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.dto.request.McpServerUpsertRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class McpServerRegistry {

    private static final TypeReference<LinkedHashMap<String, McpProperties.ServerProperties>> STORE_TYPE = new TypeReference<>() {
    };
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final Path customConfigPath;
    private final Map<String, McpProperties.ServerProperties> customServers = new ConcurrentHashMap<>();

    public McpServerRegistry(McpProperties properties,
                             ObjectMapper objectMapper,
                             @Value("${app.mcp.custom-config-path:data/mcp/custom-servers.json}") String customConfigPath) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.customConfigPath = Path.of(customConfigPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void load() {
        synchronized (customServers) {
            customServers.clear();
            customServers.putAll(readStore());
        }
    }

    public Map<String, McpProperties.ServerProperties> getAllServers() {
        LinkedHashMap<String, McpProperties.ServerProperties> merged = new LinkedHashMap<>();
        if (properties.getServers() != null) {
            merged.putAll(properties.getServers());
        }
        synchronized (customServers) {
            merged.putAll(customServers);
        }
        return merged;
    }

    public McpProperties.ServerProperties getServer(String serverId) {
        return getAllServers().get(serverId);
    }

    public boolean isCustomServer(String serverId) {
        synchronized (customServers) {
            return customServers.containsKey(serverId);
        }
    }

    public Set<String> getCustomServerIds() {
        synchronized (customServers) {
            return Set.copyOf(customServers.keySet());
        }
    }

    public void createCustomServer(McpServerUpsertRequest request) {
        String serverId = normalizeServerId(request.getServerId());
        synchronized (customServers) {
            if (properties.getServers() != null && properties.getServers().containsKey(serverId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP server already exists in built-in config: " + serverId);
            }
            if (customServers.containsKey(serverId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Custom MCP server already exists: " + serverId);
            }
            customServers.put(serverId, toServerProperties(request));
            persist();
        }
    }

    public void updateCustomServer(String serverId, McpServerUpsertRequest request) {
        String normalized = normalizeServerId(serverId);
        synchronized (customServers) {
            if (!customServers.containsKey(normalized)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Custom MCP server not found: " + normalized);
            }
            McpProperties.ServerProperties updated = toServerProperties(request);
            customServers.put(normalized, updated);
            persist();
        }
    }

    public void deleteCustomServer(String serverId) {
        String normalized = normalizeServerId(serverId);
        synchronized (customServers) {
            if (customServers.remove(normalized) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Custom MCP server not found: " + normalized);
            }
            persist();
        }
    }

    private McpProperties.ServerProperties toServerProperties(McpServerUpsertRequest request) {
        McpProperties.ServerProperties server = new McpProperties.ServerProperties();
        server.setEnabled(request.getEnabled() == null || request.getEnabled());
        server.setType(StringUtils.hasText(request.getType()) ? request.getType().trim() : "streamable-http");
        server.setBaseUrl(trimToNull(request.getBaseUrl()));
        server.setApiKey(trimToNull(request.getApiKey()));
        server.setToolNamePrefix(trimToNull(request.getToolNamePrefix()));
        server.setAllowedTools(copyList(request.getAllowedTools()));
        server.setCommand(trimToNull(request.getCommand()));
        server.setArgs(copyList(request.getArgs()));
        server.setWorkingDirectory(trimToNull(request.getWorkingDirectory()));
        server.setHeaders(copyMap(request.getHeaders()));
        server.setEnvironment(copyMap(request.getEnvironment()));
        return server;
    }

    private LinkedHashMap<String, McpProperties.ServerProperties> readStore() {
        if (!Files.exists(customConfigPath)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(customConfigPath.toFile(), STORE_TYPE);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to read custom MCP config: " + ex.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(customConfigPath.getParent());
            LinkedHashMap<String, McpProperties.ServerProperties> ordered = new LinkedHashMap<>();
            customServers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(customConfigPath.toFile(), ordered);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to persist custom MCP config: " + ex.getMessage());
        }
    }

    private String normalizeServerId(String serverId) {
        String normalized = trimToNull(serverId);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "serverId is required");
        }
        if (!SERVER_ID_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "serverId may only contain letters, numbers, dot, dash, and underscore");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private Map<String, String> copyMap(Map<String, String> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }
}
