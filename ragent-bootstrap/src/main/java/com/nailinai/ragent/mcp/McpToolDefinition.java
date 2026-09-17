package com.nailinai.ragent.mcp;

import java.util.Map;

public record McpToolDefinition(
        String serverId,
        String serverLabel,
        String exposedName,
        String remoteName,
        String description,
        Map<String, Object> inputSchema,
        ToolAnnotations annotations
) {

    public McpToolDefinition {
        annotations = annotations == null ? ToolAnnotations.UNKNOWN : annotations;
    }

    /** 兼容旧构造：未携带风险标注时按最保守档位处理（fail-safe，而非按安全放行）。 */
    public McpToolDefinition(String serverId,
                             String serverLabel,
                             String exposedName,
                             String remoteName,
                             String description,
                             Map<String, Object> inputSchema) {
        this(serverId, serverLabel, exposedName, remoteName, description, inputSchema, ToolAnnotations.UNKNOWN);
    }
}
