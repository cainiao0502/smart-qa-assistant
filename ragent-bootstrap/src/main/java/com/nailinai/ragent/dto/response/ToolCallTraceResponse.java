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
    /** 失败分类（仅失败时填充，取值为 {@code ToolFailureType} 的枚举名），便于前端与日志区分失败原因 */
    private String failureType;
}
