package com.nailinai.ragent.infra.vision;

import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.LlmRequest;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenAI 兼容多模态接口的视觉客户端。
 *
 * <p>请求体沿用标准多模态格式：{@code content} 是数组，包含 text 与 image_url 两部分；
 * image_url 使用 base64 data URL，避免要求模型侧能访问到本地文件。
 *
 * <p>注意：{@link LlmRequest} 的 messages 类型是 {@code List<Map<String, Object>>}，
 * 正是为了让这类「非纯文本 content」可以直接透传，无需改动 infra 层的请求组装逻辑。
 */
public class OpenAICompatibleVisionClient implements VisionClient {

    private final ChatClient chatClient;

    public OpenAICompatibleVisionClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe(byte[] imageBytes, String mimeType, String prompt) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String dataUrl = "data:%s;base64,%s".formatted(
                (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType,
                Base64.getEncoder().encodeToString(imageBytes)
        );

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                )
        );

        ChatResponse response = chatClient.chat(new LlmRequest(
                List.of(message), List.of(), "auto", 0.2, null));
        return response == null ? null : response.content();
    }
}
