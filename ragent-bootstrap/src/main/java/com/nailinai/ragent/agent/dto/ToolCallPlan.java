package com.nailinai.ragent.agent.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ToolCallPlan {

    private String action;
    private String tool;
    private Map<String, Object> arguments;

    public boolean shouldCallTool() {
        return "tool_call".equalsIgnoreCase(action) && tool != null && !tool.isBlank();
    }
}
