package com.nailinai.ragent.agent.dto;

import com.nailinai.ragent.infra.chat.ToolCall;
import com.nailinai.ragent.infra.chat.TokenUsage;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PlannerDecision {

    private List<AgentPlanItem> plan;
    private PlannerCurrentAction currentAction;

    /**
     * 同一响应中除主调用外的其余业务工具调用。
     *
     * <p>模型常在一轮里发起多个相互独立的调用（原生并行工具调用协议）。运行时会按序
     * 执行它们并全部回灌观察，而不是像旧实现那样只执行第一个、其余要求模型重新发起
     * ——重复决策每次都要多付一次 Planner 调用与一步预算。</p>
     */
    private List<ToolCall> additionalToolCalls;

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
