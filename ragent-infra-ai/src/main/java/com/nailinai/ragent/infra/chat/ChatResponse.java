package com.nailinai.ragent.infra.chat;

public record ChatResponse(
        String content,
        String provider
) {
}