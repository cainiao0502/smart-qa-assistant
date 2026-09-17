package com.nailinai.ragent.agent.dto;

/**
 * 一次 Agent 运行的成本与耗时统计（口径参考 pi 的 telemetry：只记 provider 上报值）。
 *
 * <p>为什么单独建一个记录而不是散落字段：这个数据要同时用于三处——落库（agent_run 审计）、
 * SSE 下发（前端"运行详情"展示）、日志排查。集中成一个类型，避免三处各自拼装导致口径漂移。
 *
 * @param llmCalls       主循环内的 LLM 调用次数（每次 Planner 决策算一次）
 * @param inputTokens    累计输入 token（含缓存命中部分）
 * @param outputTokens   累计输出 token
 * @param cachedTokens   输入中命中 prompt 缓存的部分（折扣价计费）
 * @param reasoningTokens 输出中属于思考（reasoning）的部分
 * @param loopDurationMs 主循环耗时（毫秒），不含最终回答生成
 */
public record RunUsage(
        int llmCalls,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        int reasoningTokens,
        long loopDurationMs
) {

    public static final RunUsage EMPTY = new RunUsage(0, 0, 0, 0, 0, 0);

    /** 未命中缓存的输入 token：按全价计费的真实输入量 */
    public int uncachedInputTokens() {
        return Math.max(0, inputTokens - cachedTokens);
    }

    public int cacheHitRatePercent() {
        return inputTokens > 0 ? Math.round(cachedTokens * 100f / inputTokens) : 0;
    }
}
