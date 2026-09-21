package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentStep;

import java.util.List;

/**
 * Agent 轮间钩子（参考 pi 的 {@code prepareNextTurn}）。
 *
 * <p>在每轮 Planner 决策<b>之前</b>、调用模型<b>之前</b>被调用。宿主可以借此
 * 在不改动 {@link AgentRuntimeService} 主循环的前提下，替换「本轮 Planner 所看到的
 * 步骤视图」——典型用途是上下文压缩：把早期步骤臃肿的观察记录摘要成一段结构化文字。</p>
 *
 * <p><b>关键约定：钩子返回的是「视图」，不是事实。</b>
 * 真实步骤仍按原样落库（agent_step）并推送给监听器（前端轨迹展示依赖完整过程），
 * 钩子的产出<b>只影响 Planner 的输入</b>。</p>
 *
 * <p>实现方应当无状态（单例复用），且必须自行保证失败安全——
 * 返回 {@code null} 即表示「沿用原视图」，循环不做任何特殊处理。</p>
 */
public interface AgentTurnHook {

    /**
     * 每轮 Planner 决策前调用。
     *
     * @param priorSteps 本轮已经产生的真实步骤（含 plan / tool_call / compaction 等）
     * @return 替换后的步骤视图；返回 {@code null} 表示沿用原视图
     */
    default List<AgentStep> beforeTurn(List<AgentStep> priorSteps) {
        return null;
    }

    /**
     * 工具调用执行前调用（对应 pi 的 {@code beforeToolCall}）。
     *
     * <p>用于把「该不该允许这次调用」的策略从主循环里剥离出去——典型用途是<b>收敛守卫</b>：
     * 模型可能换个说法反复检索同一件事，把步数全烧在一个子问题上，导致后面的任务没有机会执行。
     * 策略在这里拦下调用，并把原因作为观察回灌，引导模型推进下一个任务或直接收尾。</p>
     *
     * <p>注意职责边界（与 pi 一致）：钩子<b>只能拦一次调用</b>，不能终止整个运行；
     * 终止由运行时按「连续被拦次数」兜底，避免策略失效时把步数耗光。</p>
     *
     * @param context 本次调用的上下文（任务、工具、参数、已消耗次数等）
     * @return 放行或拦截；默认放行
     */
    default ToolGateDecision beforeToolCall(ToolGateContext context) {
        return ToolGateDecision.allow();
    }

    /**
     * 上一次 {@link #beforeTurn} 期间钩子自身消耗的 LLM 用量（如上下文压缩的摘要调用）。
     *
     * <p>约定：运行时在每次 {@code beforeTurn} 之后立刻读取一次并计入 run 的用量统计，
     * 因此实现方只需在 beforeTurn 内记录本次调用产生的用量；本轮钩子没有额外
     * LLM 调用时返回 {@code null} 表示「无可计入量」。字段口径与
     * {@link com.nailinai.ragent.agent.dto.RunUsage} 对齐，便于运行时直接累加。</p>
     */
    default TokenUsageReport lastTurnUsage() {
        return null;
    }

    /**
     * 钩子侧 LLM 用量上报（不含主循环 Planner 调用）。
     *
     * @param llmCalls        钩子内发生的 LLM 调用次数
     * @param inputTokens     累计输入 token（含缓存命中部分）
     * @param outputTokens    累计输出 token
     * @param cachedTokens    输入中命中 prompt 缓存的部分
     * @param reasoningTokens 输出中属于思考（reasoning）的部分
     */
    record TokenUsageReport(int llmCalls, int inputTokens, int outputTokens,
                            int cachedTokens, int reasoningTokens) {
    }
}
