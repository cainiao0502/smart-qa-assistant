package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;

import java.util.List;

public interface AgentRuntimeListener {

    AgentRuntimeListener NOOP = new AgentRuntimeListener() {
    };

    default void onRunCreated(AgentRun run) {
    }

    default void onStepStarted(AgentStep step) {
    }

    default void onPlanUpdated(String runId, List<AgentPlanItem> plan, String currentActionKey, List<String> completedTaskKeys) {
    }

    default void onStepCompleted(AgentStep step) {
    }

    default void onToolResult(ToolCallTraceResponse trace) {
    }

    default void onRunCompleted(AgentRun run) {
    }
}
