package com.nailinai.ragent.mcp;

import java.util.Map;

public record McpToolDefinition(
        String serverId,
        String serverLabel,
        String exposedName,
        String remoteName,
        String description,
        Map<String, Object> inputSchema
) {
}
