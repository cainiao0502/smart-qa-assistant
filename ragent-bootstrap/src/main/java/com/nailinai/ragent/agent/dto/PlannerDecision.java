package com.nailinai.ragent.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PlannerDecision {

    private List<AgentPlanItem> plan;
    private PlannerCurrentAction currentAction;

    public boolean shouldCallTool() {
        return "tool_call".equalsIgnoreCase(getAction()) && getTool() != null && !getTool().isBlank();
    }

    public boolean shouldFinish() {
        return "finish".equalsIgnoreCase(getAction());
    }

    public boolean shouldRespondWithGap() {
        return "respond_with_gap".equalsIgnoreCase(getAction());
    }

    public String getAction() {
        return currentAction == null ? null : currentAction.getAction();
    }

    public String getTool() {
        return currentAction == null ? null : currentAction.getTool();
    }

    public Map<String, Object> getArguments() {
        return currentAction == null ? null : currentAction.getArguments();
    }

    public String getResponse() {
        return currentAction == null ? null : currentAction.getResponse();
    }

    public String getReason() {
        return currentAction == null ? null : currentAction.getReason();
    }

    public String getTaskKey() {
        return currentAction == null ? null : currentAction.getTaskKey();
    }
}
