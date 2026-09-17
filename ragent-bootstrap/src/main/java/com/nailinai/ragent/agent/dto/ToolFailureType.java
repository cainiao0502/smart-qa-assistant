package com.nailinai.ragent.agent.dto;

/**
 * 工具失败分类。
 *
 * <p>参照 pi 的 agent-loop：pi 在 6 个不同阶段（工具不存在 / 参数校验失败 / 钩子拦截 /
 * 执行异常 / 钩子异常 / 输出截断）都产出<b>结构化的错误结果</b>，而不是抛异常打断循环。
 *
 * <p>分类的真正意义在于<b>回灌给模型的提示不同</b>：
 * 「工具不存在」应引导模型换工具，「参数不合法」应引导模型修正参数，
 * 「超时」应引导模型缩小范围。若一律回一句 "tool failed"，模型只能盲目重试。
 */
public enum ToolFailureType {

    /** 模型请求了不存在的工具 */
    NOT_FOUND,

    /** 参数不合法（工具自身校验失败） */
    INVALID_ARGUMENTS,

    /** 执行超时 */
    TIMEOUT,

    /** 执行前被取消 */
    ABORTED,

    /** 其他执行期异常 */
    EXECUTION_ERROR;

    /**
     * 拼装给模型看的观察结果：说明失败类型 + 下一步该怎么应对。
     *
     * @param toolName       工具名
     * @param cause          失败原因（可为 null）
     * @param availableTools 当前可用工具名列表（仅 NOT_FOUND 分支使用）
     */
    public String toObservation(String toolName, String cause, String availableTools) {
        String suffix = (cause == null || cause.isBlank()) ? "" : " Cause: " + cause;
        return switch (this) {
            case NOT_FOUND -> "Tool \"%s\" does not exist.%s Available tools: %s. Use one of them, or finish and answer with what you already have."
                    .formatted(toolName, suffix, availableTools);
            case INVALID_ARGUMENTS -> "Tool \"%s\" rejected its arguments.%s Fix the arguments and call it again."
                    .formatted(toolName, suffix);
            case TIMEOUT -> "Tool \"%s\" timed out.%s Retry with narrower arguments, or use a different tool."
                    .formatted(toolName, suffix);
            case ABORTED -> "Tool \"%s\" was aborted before it finished.%s".formatted(toolName, suffix);
            case EXECUTION_ERROR -> "Tool \"%s\" failed unexpectedly.%s You may retry once, use a different tool, or finish with gap=true."
                    .formatted(toolName, suffix);
        };
    }
}
