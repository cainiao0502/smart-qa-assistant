package com.nailinai.ragent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    private boolean enabled;
    private long requestTimeoutMs = 30000;
    private long toolCacheSeconds = 300;
    private Map<String, ServerProperties> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public long getToolCacheSeconds() {
        return toolCacheSeconds;
    }

    public void setToolCacheSeconds(long toolCacheSeconds) {
        this.toolCacheSeconds = toolCacheSeconds;
    }

    public Map<String, ServerProperties> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerProperties> servers) {
        this.servers = servers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerProperties {
        private boolean enabled = true;
        private String type = "streamable-http";
        private String baseUrl;
        private String apiKey;
        private String toolNamePrefix;
        private List<String> allowedTools;
        private String command;
        private List<String> args;
        private List<String> launchCommand;
        private String workingDirectory;
        private Map<String, String> headers = new LinkedHashMap<>();
        private Map<String, String> environment = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getToolNamePrefix() {
            return toolNamePrefix;
        }

        public void setToolNamePrefix(String toolNamePrefix) {
            this.toolNamePrefix = toolNamePrefix;
        }

        public List<String> getAllowedTools() {
            return allowedTools;
        }

        public void setAllowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public List<String> getLaunchCommand() {
            if (launchCommand != null && !launchCommand.isEmpty()) {
                return launchCommand;
            }
            if (!StringUtils.hasText(command)) {
                return List.of();
            }
            List<String> merged = new ArrayList<>();
            merged.add(command.trim());
            if (args != null && !args.isEmpty()) {
                merged.addAll(args);
            }
            return List.copyOf(merged);
        }

        public void setLaunchCommand(List<String> launchCommand) {
            this.launchCommand = launchCommand;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> environment) {
            this.environment = environment;
        }

        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(type);
        }

        public boolean isSse() {
            return "sse".equalsIgnoreCase(type);
        }

        public boolean isHttpLike() {
            return !isStdio();
        }
    }
}
