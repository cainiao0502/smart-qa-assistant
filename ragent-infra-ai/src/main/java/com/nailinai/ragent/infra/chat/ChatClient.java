package com.nailinai.ragent.infra.chat;

import java.util.function.Consumer;

public interface ChatClient {

    String name();

    ChatResponse chat(ChatRequest request);

    void streamChat(ChatRequest request, StreamCallback callback);

    default String chat(String prompt) {
        return chat(ChatRequest.of(prompt)).content();
    }

    default void streamChat(String prompt, Consumer<String> onReasoning, Consumer<String> onContent) {
        streamChat(ChatRequest.of(prompt), new StreamCallback() {
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