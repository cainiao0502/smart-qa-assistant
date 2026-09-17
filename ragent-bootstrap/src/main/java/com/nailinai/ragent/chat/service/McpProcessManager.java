package com.nailinai.ragent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.dto.response.McpProcessStatusResponse;

public interface McpProcessManager {

    McpProcessStatusResponse getStatus(String serverId, McpProperties.ServerProperties server);

    McpProcessStatusResponse start(String serverId, McpProperties.ServerProperties server);

    McpProcessStatusResponse stop(String serverId);

    /**
     * 通过 stdio 发送一个 JSON-RPC 请求并等待响应。
     *
     * @param timeoutMs 等待响应的上限（毫秒）。stdio 是阻塞读，没有这个上限的话
     *                  子进程一旦卡住，持有锁的调用线程和后续所有排队调用会一起挂死。
     */
    JsonNode sendStdioRequest(String serverId,
                              McpProperties.ServerProperties server,
                              String requestId,
                              String payloadJson,
                              long timeoutMs);

    void sendStdioNotification(String serverId,
                               McpProperties.ServerProperties server,
                               String payloadJson);
}
