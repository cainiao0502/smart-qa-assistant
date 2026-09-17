package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行器。
 *
 * <p>本接口承担两个职责：
 * <ol>
 *   <li><b>描述</b>——通过 {@link #getToolName()} / {@link #getDescription()} / {@link #getParametersSchema()}
 *       向模型声明「这个工具是什么、要传什么参数」（provider 原生 function calling 的契约）；</li>
 *   <li><b>执行</b>——通过 {@link #execute(Map, ToolContext)} 完成实际工作。</li>
 * </ol>
 */
public interface ToolExecutor {

    String getToolName();

    default String getDisplayName() {
        return getToolName();
    }

    default String getDescription() {
        return getDisplayName();
    }

    default String getSource() {
        return "builtin";
    }

    /**
     * 参数的 JSON Schema，用于向模型声明工具契约。
     *
     * <p>默认返回无参 schema，保证既有实现类无需改动即可编译。
     * <b>有参数的工具必须覆盖本方法</b>，否则模型无从得知该传什么参数。
     */
    default Map<String, Object> getParametersSchema() {
        return objectSchema(Map.of(), List.of());
    }

    ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context);

    // ------------------------------------------------------------------
    // schema 组装辅助方法：避免各工具实现里重复拼 Map
    // ------------------------------------------------------------------

    /** 构造 object 类型的 JSON Schema */
    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static Map<String, Object> integerProperty(String description, int minimum, int maximum) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    static Map<String, Object> numberProperty(String description, double minimum, double maximum) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "number");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }
}
