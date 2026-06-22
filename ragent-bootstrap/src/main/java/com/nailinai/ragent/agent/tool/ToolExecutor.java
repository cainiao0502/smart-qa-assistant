package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;

import java.util.Map;

public interface ToolExecutor {

    String getToolName();

    default String getDisplayName() {
        return getToolName();
    }

    default String getDescription() {
        return getDisplayName();
    }

    default String getSource() {
        return "builtin";
    }

    ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context);
}
