package com.nailinai.ragent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 收敛守卫：限制<b>单个任务</b>的工具调用次数。
 *
 * <p>现实问题：模型对同一个子问题会换个措辞反复检索（"备份频率 保留策略" → "备份 保留周期" → …），
 * 每次都成功但拿不到新信息，直到把 maxSteps 耗尽——结果是第一个子问题被反复查、
 * 后面的子问题根本没机会执行，用户拿到 PARTIAL 的半截答案。</p>
 *
 * <p>策略本身只做一件事：同一任务的调用次数达到上限后，拦下后续调用并在观察中说明原因。
 * 与 pi 的设计一致——循环只提供挂载点，判据由策略决定，策略可通过配置关闭或替换。</p>
 */
@Component
public class PerTaskToolBudgetHook implements AgentTurnHook {

    private static final Logger log = LoggerFactory.getLogger(PerTaskToolBudgetHook.class);

    private final int maxToolCallsPerTask;

    public PerTaskToolBudgetHook(
            @Value("${app.agent.max-tool-calls-per-task:3}") int maxToolCallsPerTask) {
        this.maxToolCallsPerTask = Math.max(1, maxToolCallsPerTask);
    }

    @Override
    public ToolGateDecision beforeToolCall(ToolGateContext context) {
        if (context == null || !StringUtils.hasText(context.taskKey())) {
            return ToolGateDecision.allow();
        }
        if (context.toolCallsForTask() < maxToolCallsPerTask) {
            return ToolGateDecision.allow();
        }

        String reason = ("任务 %s 已调用工具 %d 次，达到单任务上限（%d）。"
                + "该任务的信息已经查过多次，继续重复检索不会有新结果："
                + "若信息足够回答，请直接调用 finish；否则请推进到下一个未完成的任务。")
                .formatted(context.taskKey(), context.toolCallsForTask(), maxToolCallsPerTask);
        log.info("Tool gate blocked call: runId={}, taskKey={}, tool={}, used={}/{}",
                context.runId(), context.taskKey(), context.toolName(),
                context.toolCallsForTask(), maxToolCallsPerTask);
        return ToolGateDecision.block(reason);
    }
}
