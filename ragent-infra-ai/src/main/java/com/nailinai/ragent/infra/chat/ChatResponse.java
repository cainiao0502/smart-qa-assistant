package com.nailinai.ragent.infra.chat;

import java.util.List;

/**
 * LLM 响应。
 *
 * <p>相比旧版仅携带 {@code content}，新增原生工具调用支持：
 * 当 {@code finishReason == "tool_calls"} 时，{@code content} 为空属正常情况，
 * 调用方应优先检查 {@link #hasToolCalls()}，而不是把空 content 当作失败。
 *
 * @param content      文本回答，工具调用轮次下可能为 null
 * @param provider     实际响应的供应商标识
 * @param toolCalls    模型请求调用的工具列表
 * @param finishReason 结束原因（stop / length / tool_calls 等）
 * @param usage        本次调用的 token 用量；供应商未返回时为 {@link TokenUsage#EMPTY}
 */
public record ChatResponse(
        String content,
        String provider,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage
) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? "" : finishReason;
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    /** 兼容旧版四参构造（未携带用量） */
    public ChatResponse(String content, String provider, List<ToolCall> toolCalls, String finishReason) {
        this(content, provider, toolCalls, finishReason, TokenUsage.EMPTY);
    }

    /** 兼容旧版两参构造 */
    public ChatResponse(String content, String provider) {
        this(content, provider, List.of(), "", TokenUsage.EMPTY);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public ToolCall firstToolCall() {
        return toolCalls.isEmpty() ? null : toolCalls.get(0);
    }

    public boolean isToolCallFinish() {
        return "tool_calls".equalsIgnoreCase(finishReason);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
