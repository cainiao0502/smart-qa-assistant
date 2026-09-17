package com.nailinai.ragent.infra.chat;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求。
 *
 * <p>本类由原 {@code ChatRequest} 改名而来，原因有二：
 * <ol>
 *   <li>消除与业务层 {@code com.nailinai.ragent.dto.request.ChatRequest} 的同名混淆；</li>
 *   <li>从「只能传一段 prompt」扩展为「可传完整消息列表 + 工具声明」，
 *       以支持 provider 原生 function calling。</li>
 * </ol>
 *
 * <p>兼容性：{@link #of(String)} 保持旧行为，所有只传 prompt 的调用点无需改动。
 *
 * @param messages    完整消息列表（role: system / user / assistant / tool）
 * @param tools       可调用工具声明；为空表示本轮不启用工具
 * @param toolChoice  {@code "auto"} / {@code "required"} / 指定工具名；为 null 时按 auto 下发
 * @param temperature 采样温度，null 时使用默认值
 * @param maxTokens   最大生成 token 数
 */
public record LlmRequest(
        List<Map<String, Object>> messages,
        List<ToolSpec> tools,
        Object toolChoice,
        Double temperature,
        Integer maxTokens
) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 单轮 prompt 请求（兼容既有调用点） */
    public static LlmRequest of(String prompt) {
        return new LlmRequest(
                List.of(Map.of("role", "user", "content", prompt == null ? "" : prompt)),
                List.of(),
                "auto",
                null,
                null
        );
    }

    /** 携带工具声明的多轮请求 */
    public static LlmRequest withTools(List<Map<String, Object>> messages, List<ToolSpec> tools) {
        return new LlmRequest(messages, tools, "auto", null, null);
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 取最后一条 user 消息的文本，供日志与 trace 使用。
     *
     * <p>注意：本方法是从消息列表中反查，不再等同于「请求内容」。
     * 需要完整上下文时应使用 {@link #messages()}。
     */
    public String prompt() {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if ("user".equals(message.get("role")) && message.get("content") instanceof String content) {
                return content;
            }
        }
        return "";
    }
}
