package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.McpServerUpsertRequest;
import com.nailinai.ragent.dto.response.McpOverviewResponse;
import com.nailinai.ragent.dto.response.McpProcessStatusResponse;
import com.nailinai.ragent.dto.response.McpServerStatusResponse;
import com.nailinai.ragent.dto.response.McpServerToolResponse;
import com.nailinai.ragent.mcp.McpClient;
import com.nailinai.ragent.mcp.McpServerRegistry;
import com.nailinai.ragent.mcp.McpToolDefinition;
import com.nailinai.ragent.chat.service.McpProcessManager;
import com.nailinai.ragent.chat.service.McpService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class McpServiceImpl implements McpService {

    private final McpProperties properties;
    private final McpClient mcpClient;
    private final McpProcessManager mcpProcessManager;
    private final McpServerRegistry mcpServerRegistry;

    public McpServiceImpl(McpProperties properties,
                          McpClient mcpClient,
                          McpProcessManager mcpProcessManager,
                          McpServerRegistry mcpServerRegistry) {
        this.properties = properties;
        this.mcpClient = mcpClient;
        this.mcpProcessManager = mcpProcessManager;
        this.mcpServerRegistry = mcpServerRegistry;
    }

    @Override
    public McpOverviewResponse overview() {
        List<McpServerStatusResponse> servers = inspectServers();

        return McpOverviewResponse.builder()
                .enabled(properties.isEnabled())
                .requestTimeoutMs(properties.getRequestTimeoutMs())
                .toolCacheSeconds(properties.getToolCacheSeconds())
                .serverCount(servers.size())
                .enabledServerCount((int) servers.stream().filter(McpServerStatusResponse::isEnabled).count())
                .availableServerCount((int) servers.stream().filter(McpServerStatusResponse::isAvailable).count())
                .totalToolCount(servers.stream().mapToInt(McpServerStatusResponse::getToolCount).sum())
                .servers(servers)
                .build();
    }

    @Override
    public McpServerStatusResponse createServer(McpServerUpsertRequest request) {
        mcpServerRegistry.createCustomServer(request);
        return inspectServer(request.getServerId(), getServer(request.getServerId()));
    }

    @Override
    public McpServerStatusResponse updateServer(String serverId, McpServerUpsertRequest request) {
        if (!mcpServerRegistry.isCustomServer(serverId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only custom MCP servers can be edited from frontend");
        }
        mcpProcessManager.stop(serverId);
        mcpClient.resetSession(serverId);
        request.setServerId(serverId);
        mcpServerRegistry.updateCustomServer(serverId, request);
        return inspectServer(serverId, getServer(serverId));
    }

    @Override
    public void deleteServer(String serverId) {
        if (!mcpServerRegistry.isCustomServer(serverId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only custom MCP servers can be deleted from frontend");
        }
        mcpProcessManager.stop(serverId);
        mcpClient.resetSession(serverId);
        mcpServerRegistry.deleteCustomServer(serverId);
    }

    @Override
    public McpServerStatusResponse startServer(String serverId) {
        McpProperties.ServerProperties server = getServer(serverId);
        mcpClient.resetSession(serverId);
        mcpProcessManager.start(serverId, server);
        return inspectServer(serverId, server);
    }

    @Override
    public McpServerStatusResponse stopServer(String serverId) {
        McpProperties.ServerProperties server = getServer(serverId);
        mcpProcessManager.stop(serverId);
        mcpClient.resetSession(serverId);
        return inspectServer(serverId, server);
    }

    private List<McpServerStatusResponse> inspectServers() {
        Map<String, McpProperties.ServerProperties> servers = mcpServerRegistry.getAllServers();
        if (servers.isEmpty()) {
            return List.of();
        }

        List<McpServerStatusResponse> responses = new ArrayList<>();
        for (Map.Entry<String, McpProperties.ServerProperties> entry : servers.entrySet()) {
            responses.add(inspectServer(entry.getKey(), entry.getValue()));
        }

        responses.sort(Comparator.comparing(McpServerStatusResponse::getServerId));
        return responses;
    }

    private McpServerStatusResponse inspectServer(String serverId, McpProperties.ServerProperties server) {
        boolean enabled = server != null && server.isEnabled();
        boolean configured = mcpClient.isConfigured(serverId, server);
        boolean hasApiKey = server != null && StringUtils.hasText(server.getApiKey());
        boolean custom = mcpServerRegistry.isCustomServer(serverId);
        int headerCount = server == null || server.getHeaders() == null ? 0 : server.getHeaders().size();
        List<String> allowedTools = server == null || server.getAllowedTools() == null ? List.of() : List.copyOf(server.getAllowedTools());
        McpProcessStatusResponse processStatus = mcpProcessManager.getStatus(serverId, server);

        String status = resolveStatus(enabled, configured);
        String errorMessage = resolveBaselineMessage(status);
        boolean available = false;
        List<McpServerToolResponse> tools = List.of();
        boolean shouldProbeTools = "AVAILABLE".equals(status) || "UNAVAILABLE".equals(status);

        if (shouldProbeTools && server != null && server.isStdio() && !processStatus.isRunning()) {
            status = "STOPPED";
            errorMessage = "Local stdio MCP process is not running";
            shouldProbeTools = false;
        }

        if (shouldProbeTools) {
            try {
                tools = mcpClient.listTools(serverId, server).stream()
                        .filter(tool -> allowTool(server, tool.remoteName()))
                        .map(this::toToolResponse)
                        .sorted(Comparator.comparing(McpServerToolResponse::getExposedName))
                        .toList();
                available = true;
                status = "AVAILABLE";
                errorMessage = null;
            } catch (RuntimeException ex) {
                status = "UNAVAILABLE";
                errorMessage = ex.getMessage();
            }
        }

        return McpServerStatusResponse.builder()
                .serverId(serverId)
                .custom(custom)
                .transportType(server == null ? "unknown" : server.getType())
                .status(status)
                .enabled(enabled)
                .configured(configured)
                .available(available)
                .baseUrl(server == null ? "" : server.getBaseUrl())
                .apiKey(server == null ? "" : server.getApiKey())
                .command(server == null ? "" : server.getCommand())
                .args(server == null || server.getArgs() == null ? List.of() : List.copyOf(server.getArgs()))
                .workingDirectory(server == null ? "" : server.getWorkingDirectory())
                .toolNamePrefix(server == null ? "" : server.getToolNamePrefix())
                .hasApiKey(hasApiKey)
                .headerCount(headerCount)
                .headers(server == null || server.getHeaders() == null ? Map.of() : Map.copyOf(server.getHeaders()))
                .environment(server == null || server.getEnvironment() == null ? Map.of() : Map.copyOf(server.getEnvironment()))
                .allowedTools(allowedTools)
                .processStatus(processStatus)
                .toolCount(tools.size())
                .errorMessage(errorMessage)
                .tools(tools)
                .build();
    }

    private McpProperties.ServerProperties getServer(String serverId) {
        McpProperties.ServerProperties server = mcpServerRegistry.getServer(serverId);
        if (server == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP server not found: " + serverId);
        }
        return server;
    }

    private String resolveStatus(boolean enabled, boolean configured) {
        if (!properties.isEnabled()) {
            return "DISABLED_GLOBAL";
        }
        if (!enabled) {
            return "DISABLED";
        }
        if (!configured) {
            return "NOT_CONFIGURED";
        }
        return "UNAVAILABLE";
    }

    private String resolveBaselineMessage(String status) {
        return switch (status) {
            case "DISABLED_GLOBAL" -> "MCP is disabled globally";
            case "DISABLED" -> "This MCP server is disabled";
            case "NOT_CONFIGURED" -> "Transport configuration is incomplete";
            default -> null;
        };
    }

    private boolean allowTool(McpProperties.ServerProperties server, String remoteName) {
        List<String> allowedTools = server.getAllowedTools();
        return allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(remoteName);
    }

    private McpServerToolResponse toToolResponse(McpToolDefinition tool) {
        return McpServerToolResponse.builder()
                .exposedName(tool.exposedName())
                .remoteName(tool.remoteName())
                .description(tool.description())
                .build();
    }
}
