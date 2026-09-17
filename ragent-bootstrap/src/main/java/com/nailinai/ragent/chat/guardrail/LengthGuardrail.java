package com.nailinai.ragent.chat.guardrail;

import com.nailinai.ragent.dto.request.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 长度护栏：拒绝超长问题，防止 token 滥用与上下文窗口溢出。
 */
@Component
public class LengthGuardrail implements InputGuardrail {

    private final int maxQuestionLength;

    public LengthGuardrail(@Value("${app.guardrail.max-question-length:2000}") int maxQuestionLength) {
        this.maxQuestionLength = Math.max(1, maxQuestionLength);
    }

    @Override
    public String name() {
        return "length";
    }

    @Override
    public GuardrailResult check(ChatRequest request) {
        String question = request == null ? null : request.getQuestion();
        if (question == null || question.isBlank()) {
            return GuardrailResult.fail("问题内容不能为空");
        }
        if (question.length() > maxQuestionLength) {
            return GuardrailResult.fail("问题长度超出限制（最大 " + maxQuestionLength + " 字符）");
        }
        return GuardrailResult.pass();
    }
}
