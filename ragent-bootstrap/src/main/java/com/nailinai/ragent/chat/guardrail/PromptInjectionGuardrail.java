package com.nailinai.ragent.chat.guardrail;

import com.nailinai.ragent.dto.request.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Prompt 注入护栏：检测用户输入中的提示注入模式（如试图覆盖系统指令、
 * 诱导模型泄露 system prompt），命中即拒绝。
 *
 * <p>使用保守的关键词规则，覆盖中英文常见注入句式；被拦截的请求不会进入
 * LLM / Agent 链路，从源头降低注入风险。
 */
@Component
public class PromptInjectionGuardrail implements InputGuardrail {

    /** 注入句式关键词（小写匹配）。 */
    private static final List<String> INJECTION_PATTERNS = List.of(
            // 覆盖/忽略系统指令
            "忽略之前的指令", "忽略以上指令", "忽略所有指令", "无视之前的指令",
            "ignore previous instructions", "ignore all previous", "ignore above",
            // 诱导泄露系统提示词
            "显示你的 system prompt", "输出你的 system prompt", "泄露你的系统提示词",
            "reveal your system prompt", "show your system prompt", "print your system prompt",
            // 角色越权 / 伪装
            "你其实是", "实际上你是"
    );

    private final boolean enabled;

    public PromptInjectionGuardrail(@Value("${app.guardrail.prompt-injection-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "prompt-injection";
    }

    @Override
    public GuardrailResult check(ChatRequest request) {
        if (!enabled || request == null || !StringUtils.hasText(request.getQuestion())) {
            return GuardrailResult.pass();
        }
        String normalized = request.getQuestion().trim().toLowerCase(Locale.ROOT);
        for (String pattern : INJECTION_PATTERNS) {
            if (normalized.contains(pattern)) {
                // 告知命中规则：规则黑名单对日常表达有误伤（如「你其实是……」），
                // 让用户知道拦了什么才能改写问题绕过误伤
                return GuardrailResult.fail("输入包含疑似提示注入内容（命中规则：" + pattern + "），已被拦截");
            }
        }
        return GuardrailResult.pass();
    }
}
