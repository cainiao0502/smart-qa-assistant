package com.nailinai.ragent.chat.guardrail;

/**
 * 输入护栏校验结果。
 *
 * @param allowed 是否放行
 * @param reason  拒绝原因（allowed 为 false 时给出，用于返回给用户）
 */
public record GuardrailResult(boolean allowed, String reason) {

    public static GuardrailResult pass() {
        return new GuardrailResult(true, null);
    }

    public static GuardrailResult fail(String reason) {
        return new GuardrailResult(false, reason);
    }
}
