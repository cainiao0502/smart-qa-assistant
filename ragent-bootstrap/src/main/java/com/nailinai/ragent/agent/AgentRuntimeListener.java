package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.ApprovalRequest;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;

import java.util.List;

public interface AgentRuntimeListener {

    AgentRuntimeListener NOOP = new AgentRuntimeListener() {
    };

    default void onRunCreated(AgentRun run) {
    }

    default void onStepStarted(AgentStep step) {
    }

    /**
     * 计划推进回调。
     *
     * @param plan              当前计划项
     * @param currentActionKey  正在推进的任务 key
     * @param completedTaskKeys 已完成任务 key
     * @param skippedTaskKeys   模型显式声明的「有意跳过」任务及原因（元素 {key, reason}），
     *                          用于区分「合理跳过」与「漏做」——前者不应在 UI 上显示为未完成
     * @param unresolvedTaskKeys 既未完成也未声明跳过的任务 key（真正需要关注的欠账）
     */
    default void onPlanUpdated(String runId,
                               List<AgentPlanItem> plan,
                               String currentActionKey,
                               List<String> completedTaskKeys,
                               List<java.util.Map<String, Object>> skippedTaskKeys,
                               List<String> unresolvedTaskKeys) {
    }

    default void onStepCompleted(AgentStep step) {
    }

    default void onToolResult(ToolCallTraceResponse trace) {
    }

    default void onRunCompleted(AgentRun run) {
    }

    /**
     * 当前监听器是否具备人工确认能力。
     *
     * <p>必须与 {@link #requestApproval} 保持一致：默认实现两者都是「不支持」。
     * 拆成两个方法是为了让策略能区分「这个请求压根没有确认通道」与「有通道但没等到回应」——
     * 前者是调用方式的问题（例如同步接口），后者是等待超时或连接中断，
     * 两者的排查方向与对用户的说明完全不同。</p>
     */
    default boolean supportsApproval() {
        return false;
    }

    /**
     * 请求人工确认一次工具调用（<b>阻塞</b>直到得出结论）。
     *
     * <p>监听器在这里的角色从「事件接收方」扩展为「请求级交互通道」——它本来就是
     * 每次 run 单独传入的实例，天然携带该次请求的连接上下文（SSE emitter、连接状态），
     * 因此不需要再额外引入一个平行的通道抽象。</p>
     *
     * <p>返回 {@link ApprovalOutcome#UNAVAILABLE} 表示问不到人（同步 HTTP 调用、
     * 客户端未实现审批界面、连接已断开）。之所以不返回「拒绝」，
     * 是为了让策略不把「系统问不到」误报成「用户不同意」。</p>
     *
     * @param request 待确认的调用内容
     * @return 审批结论
     */
    default ApprovalOutcome requestApproval(ApprovalRequest request) {
        return ApprovalOutcome.UNAVAILABLE;
    }
}
