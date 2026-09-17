package com.nailinai.ragent.infra.chat;

import java.util.Map;

/**
 * 模型返回的工具调用请求。
 *
 * @param id           调用标识，回灌工具结果消息（role=tool）时必须原样带回
 * @param name         工具名
 * @param arguments    已解析的参数
 * @param rawArguments 模型返回的原始参数文本，仅用于日志与 trace
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments,
        String rawArguments
) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments, null);
    }
}
