package com.nailinai.ragent.agent;

/**
 * 工具闸门决策。
 *
 * <p>与 pi 的 {@code beforeToolCall} 返回 {@code {block: true}} 等价：策略只能"拦一次调用"，
 * 不能直接终止整个运行——终止由运行时按连续拦截次数兜底，职责边界清晰。</p>
 *
 * <p><b>{@code category} 是必需的</b>：不同策略拦下调用后，运行时该做的事不一样。
 * 预算类拦截意味着「这个任务已经用掉了它的调用机会」，需要把它从可推进目标里摘掉，
 * 好让模型换下一个任务；而授权类拦截只是「这一次没被批准」，
 * 任务本身没有用尽预算，把任务封顶会让用户误以为整个任务失败了。</p>
 *
 * @param blocked  是否拦截本次调用
 * @param reason   拦截原因（会作为观察回灌给模型，让它知道为什么被拦、下一步该做什么）
 * @param category 策略类别，决定运行时的后续处理
 */
public record ToolGateDecision(boolean blocked, String reason, Category category) {

    public enum Category {
        /** 调用预算类：任务已耗尽调用机会，需封顶该任务并推进下一个 */
        BUDGET,
        /** 授权类：需要人工批准；拒绝只影响本次调用，不消耗任务预算 */
        APPROVAL,
        /** 其他策略类：同样只影响本次调用 */
        POLICY
    }

    public static ToolGateDecision allow() {
        return new ToolGateDecision(false, null, null);
    }

    /** 兼容既有调用：不带类别时按预算类处理（当前唯一的旧调用方是任务预算守卫）。 */
    public static ToolGateDecision block(String reason) {
        return new ToolGateDecision(true, reason, Category.BUDGET);
    }

    public static ToolGateDecision block(String reason, Category category) {
        return new ToolGateDecision(true, reason, category == null ? Category.POLICY : category);
    }
}
