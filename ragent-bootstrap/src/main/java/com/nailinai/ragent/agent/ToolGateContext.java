package com.nailinai.ragent.agent;

import java.util.Map;

/**
 * 工具调用前的闸门上下文（对应 pi {@code AgentLoopConfig.beforeToolCall} 的入参）。
 *
 * <p>守卫策略需要的判据在这里一次性给全，策略本身保持无状态——计数与状态由运行时维护，
 * 这样单例 Bean 不需要按 runId 做并发隔离。</p>
 *
 * @param runId            本次运行标识
 * @param taskKey          当前任务 key（计划为空时为 null）
 * @param toolName         模型请求调用的工具名
 * @param arguments        调用参数
 * @param toolCallsForTask 当前任务已消耗的工具调用次数
 * @param guardBlockCount  本次运行累计被守卫拦截的次数
 * @param approvalRequester 审批通道；为 null 表示当前请求没有可用的交互通道，
 *                          需要人工确认的策略应据此降级（而不是默认放行）
 */
public record ToolGateContext(
        String runId,
        String taskKey,
        String toolName,
        Map<String, Object> arguments,
        int toolCallsForTask,
        int guardBlockCount,
        ApprovalRequester approvalRequester
) {

    public ToolGateContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** 兼容无审批通道的构造（既有策略与测试不受影响）。 */
    public ToolGateContext(String runId,
                           String taskKey,
                           String toolName,
                           Map<String, Object> arguments,
                           int toolCallsForTask,
                           int guardBlockCount) {
        this(runId, taskKey, toolName, arguments, toolCallsForTask, guardBlockCount, null);
    }

    /** 当前请求是否具备向人提问的能力 */
    public boolean hasApprovalChannel() {
        return approvalRequester != null;
    }
}
