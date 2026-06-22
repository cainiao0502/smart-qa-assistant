package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class McpOverviewResponse {

    private boolean enabled;
    private long requestTimeoutMs;
    private long toolCacheSeconds;
    private int serverCount;
    private int enabledServerCount;
    private int availableServerCount;
    private int totalToolCount;
    private List<McpServerStatusResponse> servers;
}
