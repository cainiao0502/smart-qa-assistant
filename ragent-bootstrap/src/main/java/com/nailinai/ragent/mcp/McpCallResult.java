package com.nailinai.ragent.mcp;

public record McpCallResult(
        boolean isError,
        String summary,
        String supplementalContext,
        Object rawResult
) {
}
