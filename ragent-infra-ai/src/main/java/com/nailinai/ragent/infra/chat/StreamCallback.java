package com.nailinai.ragent.infra.chat;

public interface StreamCallback {

    void onReasoning(String delta);

    void onContent(String delta);

    default void onComplete() {
    }

    default void onError(Throwable ex) {
        throw new IllegalStateException(ex);
    }
}