package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class McpServerStatusResponse {

    private String serverId;
    private boolean custom;
    private String transportType;
    private String status;
    private boolean enabled;
    private boolean configured;
    private boolean available;
    private String baseUrl;
    private String apiKey;
    private String command;
    private List<String> args;
    private String workingDirectory;
    private String toolNamePrefix;
    private boolean hasApiKey;
    private int headerCount;
    private Map<String, String> headers;
    private Map<String, String> environment;
    private List<String> allowedTools;
    private McpProcessStatusResponse processStatus;
    private int toolCount;
    private String errorMessage;
    private List<McpServerToolResponse> tools;
}
