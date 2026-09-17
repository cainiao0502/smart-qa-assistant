package com.nailinai.ragent.infra.chat;

/**
 * 单次 LLM 调用的 token 用量。
 *
 * <p>数值来自供应商响应（"Reported"），不在本地按价格表估算——与 pi 的遥测口径一致：
 * harness 只负责如实记录 provider 上报的用量，成本换算交给外部。
 *
 * <p><b>为什么必须区分缓存命中：</b>LLM API 是无状态的，每次请求都要重发完整前缀
 * （system prompt + 工具 schema + 历史）。在 agent 循环里这个前缀高度重复，
 * 供应商的 prompt caching 会让其中绝大部分按折扣价计费——实测同一前缀第二次请求
 * 2135 tokens 中 1920 命中缓存。因此只看 {@code inputTokens} 会严重高估成本。
 *
 * @param inputTokens     输入 token 总量（含缓存命中部分）
 * @param outputTokens    输出 token
 * @param cachedTokens    输入中命中 prompt 缓存的部分（按折扣价计费）
 * @param reasoningTokens 输出中属于思考（reasoning）的部分
 */
public record TokenUsage(int inputTokens, int outputTokens, int cachedTokens, int reasoningTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

    public TokenUsage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    public int total() {
        return inputTokens + outputTokens;
    }

    /** 未命中缓存的输入 token：这部分才是按全价计费的输入量 */
    public int uncachedInputTokens() {
        return Math.max(0, inputTokens - cachedTokens);
    }

    public boolean isEmpty() {
        return inputTokens <= 0 && outputTokens <= 0;
    }

    public static TokenUsage of(Integer inputTokens, Integer outputTokens) {
        return new TokenUsage(
                inputTokens == null ? 0 : Math.max(0, inputTokens),
                outputTokens == null ? 0 : Math.max(0, outputTokens),
                0,
                0);
    }

    public static TokenUsage of(Integer inputTokens, Integer outputTokens,
                                Integer cachedTokens, Integer reasoningTokens) {
        return new TokenUsage(
                inputTokens == null ? 0 : Math.max(0, inputTokens),
                outputTokens == null ? 0 : Math.max(0, outputTokens),
                cachedTokens == null ? 0 : Math.max(0, cachedTokens),
                reasoningTokens == null ? 0 : Math.max(0, reasoningTokens));
    }
}
