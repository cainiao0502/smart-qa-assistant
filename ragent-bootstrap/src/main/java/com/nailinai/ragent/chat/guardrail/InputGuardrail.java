package com.nailinai.ragent.chat.guardrail;

import com.nailinai.ragent.dto.request.ChatRequest;

/**
 * 输入护栏：在请求进入 LLM / Agent 链路之前执行一次校验。
 *
 * <p>参考 OpenAI Agents SDK 的 Guardrail 设计：把安全规则从业务逻辑中
 * 抽离为可组合、可单独测试的组件。规则实现只需关心"是否放行"，
 * 组合与编排由 {@link GuardrailManager} 负责。
 */
public interface InputGuardrail {

    /**
     * @return 规则名称（用于日志与排障）
     */
    String name();

    /**
     * 校验请求，返回是否放行及拒绝原因。
     */
    GuardrailResult check(ChatRequest request);
}
