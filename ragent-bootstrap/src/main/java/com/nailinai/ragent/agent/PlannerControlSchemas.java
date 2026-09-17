package com.nailinai.ragent.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner 控制类工具的 JSON Schema 定义。
 *
 * <p>「提交计划」与「结束」这两件事过去是 JSON 决策块里的字段，现在改为工具调用，
 * 因此需要像业务工具一样向模型声明参数契约。
 */
final class PlannerControlSchemas {

    private PlannerControlSchemas() {
    }

    /** {@code submit_plan}: 发布/更新执行计划 */
    static Map<String, Object> submitPlanSchema() {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("key", Map.of("type", "string", "description", "任务稳定标识，如 task_1"));
        itemProperties.put("title", Map.of("type", "string", "description", "任务标题"));
        itemProperties.put("description", Map.of("type", "string", "description", "任务说明，可为空"));

        Map<String, Object> taskItem = new LinkedHashMap<>();
        taskItem.put("type", "object");
        taskItem.put("properties", itemProperties);
        taskItem.put("required", List.of("key", "title"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tasks", Map.of(
                "type", "array",
                "description", "2-5 个任务，最后一个必须是最终回答任务",
                "items", taskItem
        ));
        properties.put("currentTaskKey", Map.of("type", "string", "description", "当前要推进的任务 key"));
        properties.put("reason", Map.of("type", "string", "description", "本次规划的理由"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("tasks", "currentTaskKey"));
        return schema;
    }

    /** {@code finish}: 结束工具调用阶段，进入最终回答 */
    static Map<String, Object> finishSchema() {
        Map<String, Object> skipItemProperties = new LinkedHashMap<>();
        skipItemProperties.put("key", Map.of("type", "string", "description", "被跳过的任务 key"));
        skipItemProperties.put("reason", Map.of("type", "string",
                "description", "跳过原因，例如「信息已被 task_1 的观察覆盖」「该任务与问题无关」"));

        Map<String, Object> skipItem = new LinkedHashMap<>();
        skipItem.put("type", "object");
        skipItem.put("properties", skipItemProperties);
        skipItem.put("required", List.of("key", "reason"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskKey", Map.of("type", "string", "description", "任务 key，通常是计划中的最终回答任务"));
        properties.put("response", Map.of("type", "string", "description", "给最终回答阶段的简短指示，可为空"));
        properties.put("reason", Map.of("type", "string", "description", "结束的理由"));
        properties.put("gap", Map.of("type", "boolean", "description", "信息不足、需要向用户说明缺口时设为 true"));
        properties.put("completedTaskKeys", Map.of(
                "type", "array",
                "description", "已完成且确实执行过的任务 key 列表（只填实际做过的）",
                "items", Map.of("type", "string")
        ));
        properties.put("skippedTaskKeys", Map.of(
                "type", "array",
                "description", "有意不单独执行的任务及其原因（跳过本身是正当的：例如信息已被前面步骤的观察覆盖）。"
                        + "凡计划中已列、本次未单独执行的任务，都必须在此声明原因，否则会被标记为「未解决」。",
                "items", skipItem
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("taskKey"));
        return schema;
    }
}
