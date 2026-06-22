package com.nailinai.ragent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.dto.response.McpProcessStatusResponse;

public interface McpProcessManager {

    McpProcessStatusResponse getStatus(String serverId, McpProperties.ServerProperties server);

    McpProcessStatusResponse start(String serverId, McpProperties.ServerProperties server);

    McpProcessStatusResponse stop(String serverId);

    JsonNode sendStdioRequest(String serverId,
                              McpProperties.ServerProperties server,
                              String requestId,
                              String payloadJson);

    void sendStdioNotification(String serverId,
                               McpProperties.ServerProperties server,
                               String payloadJson);
}
