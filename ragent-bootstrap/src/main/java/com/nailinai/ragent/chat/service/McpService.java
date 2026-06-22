package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.dto.request.McpServerUpsertRequest;
import com.nailinai.ragent.dto.response.McpOverviewResponse;
import com.nailinai.ragent.dto.response.McpServerStatusResponse;

public interface McpService {

    McpOverviewResponse overview();

    McpServerStatusResponse createServer(McpServerUpsertRequest request);

    McpServerStatusResponse updateServer(String serverId, McpServerUpsertRequest request);

    void deleteServer(String serverId);

    McpServerStatusResponse startServer(String serverId);

    McpServerStatusResponse stopServer(String serverId);
}
