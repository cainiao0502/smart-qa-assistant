package com.nailinai.ragent.infra.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具声明，用于向模型描述一个可调用的工具（OpenAI function calling 协议）。
 *
 * <p>区别于 {@code com.nailinai.ragent.agent.tool.ToolExecutor}：后者是「工具怎么执行」，
 * 本类型是「工具怎么向模型描述」。两者由 {@code ToolExecutorRegistry} 桥接。
 *
 * @param name        工具名（模型调用时回传的标识）
 * @param description 自然语言描述，模型据此判断何时使用
 * @param parameters  JSON Schema 形式的参数定义
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, Object> parameters
) {

    private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object", "properties", Map.of());

    public ToolSpec {
        if (parameters == null || parameters.isEmpty()) {
            parameters = EMPTY_SCHEMA;
        }
    }

    public static ToolSpec of(String name, String description, Map<String, Object> parameters) {
        return new ToolSpec(name, description, parameters);
    }

    /**
     * 转换为 OpenAI {@code tools} 数组中的单个元素：
     * <pre>{"type":"function","function":{"name":..,"description":..,"parameters":..}}</pre>
     */
    public Map<String, Object> toOpenAiFormat() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description == null || description.isBlank() ? name : description);
        function.put("parameters", parameters);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }
}
