package com.nailinai.ragent.agent.dto;

import com.nailinai.ragent.infra.chat.TokenUsage;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PlannerDecision {

    private List<AgentPlanItem> plan;
    private PlannerCurrentAction currentAction;

    /** 本次 Planner（LLM）调用的 token 用量，供主循环累计成本 */
    private TokenUsage usage = TokenUsage.EMPTY;

    /** 模型显式声明的「已完成」任务 key（仅 finish 阶段填写，可空） */
    private List<String> completedTaskKeys;

    /** 模型显式声明的「有意跳过」任务及原因，元素形如 {key, reason}（仅 finish 阶段填写，可空） */
    private List<Map<String, Object>> skippedTaskKeys;

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
