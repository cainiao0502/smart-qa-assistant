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

    /**
     * 仅从缓存中查找工具定义，<b>不触发服务器加载</b>。
     *
     * <p>工具闸门在每次调用前都要问一次「这个工具什么风险等级」，属于热路径：
     * 不能为了判定风险就去遍历并启动一遍外部进程。实际流程里，模型能看见某个 MCP 工具，
     * 说明工具列表已经被拉取过（planner 组装工具声明时调用过 {@link #listAllTools()}），
     * 此时缓存必然命中；缓存缺失时返回 null，由调用方按最保守档位处理。</p>
     */
    public McpToolDefinition findByExposedNameCached(String exposedName) {
        if (!StringUtils.hasText(exposedName)) {
            return null;
        }
        for (CacheEntry entry : cache.values()) {
            for (McpToolDefinition tool : entry.tools()) {
                if (exposedName.equals(tool.exposedName())) {
                    return tool;
                }
            }
        }
        return null;
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
