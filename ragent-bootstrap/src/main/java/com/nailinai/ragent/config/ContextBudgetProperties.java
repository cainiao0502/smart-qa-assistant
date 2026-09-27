package com.nailinai.ragent.config;

import com.nailinai.ragent.util.PromptBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上下文预算配置。
 *
 * <p>把「模型窗口有多大」这件事从散落各处的魔法数收敛成一份显式配置。此前项目里
 * 检索切片、历史消息、步骤观察各有自己的上限（条数 / 字符数 / token 数三种量纲），
 * 但它们彼此不可加，也没有任何一处与模型窗口比较过——系统不知道自己会不会超，
 * 只能等供应商返回 400。
 *
 * <p>{@code window-tokens} 建议按<b>所有候选供应商中最小的窗口</b>配置：
 * {@code ModelRouter} 会在故障时切换到下一个候选，若按最大的那个配，
 * 切换后就会出现「原本刚好、切换后溢出」的诡异故障。
 *
 * <p>⚠️ {@code reserved-output-tokens} 是<b>假设而非事实</b>：本项目只在
 * {@code ContextCompactionService} 的摘要请求上显式下发 {@code max_tokens}，
 * Planner 与最终回答请求都不带该字段，真实输出占用由供应商默认上限决定。
 * 因此该值必须 &gt;= 所有候选供应商的默认输出上限，否则输出预留会被系统性低估
 * （多候选路由会切换供应商，而各家默认上限并不相同）。
 */
@Component
public class ContextBudgetProperties {

    private final boolean enabled;
    private final int windowTokens;
    private final int reservedOutputTokens;
    private final double safetyRatio;

    public ContextBudgetProperties(
            @Value("${app.context-budget.enabled:true}") boolean enabled,
            @Value("${app.context-budget.window-tokens:128000}") int windowTokens,
            @Value("${app.context-budget.reserved-output-tokens:4096}") int reservedOutputTokens,
            @Value("${app.context-budget.safety-ratio:0.9}") double safetyRatio) {
        this.enabled = enabled;
        this.windowTokens = windowTokens;
        this.reservedOutputTokens = reservedOutputTokens;
        this.safetyRatio = safetyRatio;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getWindowTokens() {
        return windowTokens;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public double getSafetyRatio() {
        return safetyRatio;
    }

    /**
     * 构造本次请求的预算策略。
     *
     * @param reservedInputTokens 预算之外仍占窗口的输入（system prompt + 工具 schema JSON）
     */
    public PromptBudget.Policy policy(int reservedInputTokens) {
        return new PromptBudget.Policy(windowTokens, reservedOutputTokens, reservedInputTokens, safetyRatio);
    }
}
