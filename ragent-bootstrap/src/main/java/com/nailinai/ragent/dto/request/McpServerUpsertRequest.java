package com.nailinai.ragent.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class McpServerUpsertRequest {

    @NotBlank(message = "serverId is required")
    private String serverId;

    private Boolean enabled = true;
    private String type = "streamable-http";
    private String baseUrl;
    private String apiKey;
    private String toolNamePrefix;
    private List<String> allowedTools;
    private String command;
    private List<String> args;
    private String workingDirectory;
    private Map<String, String> headers;
    private Map<String, String> environment;
}
