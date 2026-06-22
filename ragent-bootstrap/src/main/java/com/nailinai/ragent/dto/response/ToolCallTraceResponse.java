package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ToolCallTraceResponse {

    private String toolName;
    private String displayName;
    private String source;
    private String status;
    private Map<String, Object> arguments;
    private String summary;
    private String resultPreview;
    private Object rawResult;
    private Long durationMs;
}
