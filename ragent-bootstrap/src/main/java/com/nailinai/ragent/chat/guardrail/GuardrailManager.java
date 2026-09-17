package com.nailinai.ragent.chat.guardrail;

import com.nailinai.ragent.dto.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输入护栏管理器：按顺序执行所有护栏规则，任一拒绝即拦截。
 *
 * <p>新增规则时只需实现 {@link InputGuardrail} 并注册为 Spring Bean，
 * 自动被装配进来；每条规则可单独测试，职责单一。
 */
@Component
public class GuardrailManager {

    private final List<InputGuardrail> guardrails;

    public GuardrailManager(List<InputGuardrail> guardrails) {
        this.guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
    }

    /**
     * 校验请求，返回第一个拒绝结果；全部通过则放行。
     */
    public GuardrailResult validate(ChatRequest request) {
        for (InputGuardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.check(request);
            if (!result.allowed()) {
                return result;
            }
        }
        return GuardrailResult.pass();
    }

    public List<String> guardrailNames() {
        return guardrails.stream().map(InputGuardrail::name).toList();
    }
}
