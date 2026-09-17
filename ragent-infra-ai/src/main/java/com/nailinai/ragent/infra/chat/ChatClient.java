package com.nailinai.ragent.infra.chat;

import java.util.function.Consumer;

/**
 * LLM 对话客户端。
 *
 * <p>请求类型由 {@code ChatRequest} 更名为 {@link LlmRequest}（消除与业务层 DTO 的同名混淆）。
 * {@link #chat(String)} 便捷方法保留，所有只传 prompt 的调用点无需改动。
 */
public interface ChatClient {

    String name();

    ChatResponse chat(LlmRequest request);

    void streamChat(LlmRequest request, StreamCallback callback);

    /** 单轮 prompt 便捷入口（兼容既有调用点） */
    default String chat(String prompt) {
        return chat(LlmRequest.of(prompt)).content();
    }

    default void streamChat(String prompt, Consumer<String> onReasoning, Consumer<String> onContent) {
        streamChat(LlmRequest.of(prompt), new StreamCallback() {
            @Override
            public void onReasoning(String delta) {
                onReasoning.accept(delta);
            }

            @Override
            public void onContent(String delta) {
                onContent.accept(delta);
            }
        });
    }
}
