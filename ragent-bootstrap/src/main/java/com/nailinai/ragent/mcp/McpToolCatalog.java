package com.nailinai.ragent.mcp;

import com.nailinai.ragent.config.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private final McpProperties properties;
    private final McpClient mcpClient;
    private final McpServerRegistry mcpServerRegistry;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public McpToolCatalog(McpProperties properties, McpClient mcpClient, McpServerRegistry mcpServerRegistry) {
        this.properties = properties;
        this.mcpClient = mcpClient;
        this.mcpServerRegistry = mcpServerRegistry;
    }

    public List<McpToolDefinition> listAllTools() {
        Map<String, McpProperties.ServerProperties> servers = mcpServerRegistry.getAllServers();
        if (!properties.isEnabled() || servers.isEmpty()) {
            return List.of();
        }

        List<McpToolDefinition> tools = new ArrayList<>();
        for (Map.Entry<String, McpProperties.ServerProperties> entry : servers.entrySet()) {
            McpProperties.ServerProperties server = entry.getValue();
            if (server == null || !server.isEnabled() || !mcpClient.isConfigured(entry.getKey(), server)) {
                continue;
            }
            try {
                tools.addAll(listServerTools(entry.getKey(), server));
            } catch (RuntimeException ex) {
                log.warn("Skip unavailable MCP server {}: {}", entry.getKey(), ex.getMessage());
            }
        }
        return tools.stream()
                .sorted(Comparator.comparing(McpToolDefinition::exposedName))
                .toList();
    }

    public McpToolDefinition findByExposedName(String exposedName) {
        return listAllTools().stream()
                .filter(tool -> tool.exposedName().equals(exposedName))
                .findFirst()
                .orElse(null);
    }

    public McpCallResult callTool(McpToolDefinition definition, Map<String, Object> arguments) {
        McpProperties.ServerProperties server = mcpServerRegistry.getServer(definition.serverId());
        return mcpClient.callTool(definition.serverId(), server, definition.remoteName(), arguments);
    }

    private List<McpToolDefinition> listServerTools(String serverId, McpProperties.ServerProperties server) {
        CacheEntry cached = cache.get(serverId);
        long now = Instant.now().getEpochSecond();
        if (cached != null && cached.expiresAtEpochSecond > now) {
            return cached.tools;
        }

        List<McpToolDefinition> discovered = mcpClient.listTools(serverId, server).stream()
                .filter(tool -> allowTool(server, tool.remoteName()))
                .collect(Collectors.toList());
        cache.put(serverId, new CacheEntry(discovered, now + Math.max(30, properties.getToolCacheSeconds())));
        return discovered;
    }

    private boolean allowTool(McpProperties.ServerProperties server, String remoteName) {
        List<String> allowedTools = server.getAllowedTools();
        return allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(remoteName);
    }

    private record CacheEntry(List<McpToolDefinition> tools, long expiresAtEpochSecond) {
    }
}
