package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.enums.MessageRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextWindowManager token 预算裁剪的单元测试。
 */
class ContextWindowManagerTest {

    private final ContextWindowManager manager = new ContextWindowManager(20);

    @Test
    @DisplayName("历史消息未超预算：原样返回")
    void withinBudget_shouldKeepAll() {
        List<ChatMessage> history = List.of(
                message("用户问题"),
                message("助手回答"));

        List<ChatMessage> result = manager.trimHistory(history, 1);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("超过预算：从最旧消息开始丢弃直到满足预算")
    void overBudget_shouldDropOldestFirst() {
        // 每条约 8 token（"这是第X条很长的历史消息内容填充内容"）
        List<ChatMessage> history = List.of(
                message("这是第1条很长的历史消息内容填充内容"),
                message("这是第2条很长的历史消息内容填充内容"),
                message("这是第3条很长的历史消息内容填充内容"),
                message("这是第4条很长的历史消息内容填充内容"));

        List<ChatMessage> result = manager.trimHistory(history, 1);

        assertThat(result).isNotEmpty();
        assertThat(result).allMatch(message -> manager.getMaxHistoryTokens()
                >= result.stream().mapToInt(m -> m.getContent().length() / 4).sum());
        // 最新的消息必须保留
        assertThat(result.get(result.size() - 1).getContent()).isEqualTo("这是第4条很长的历史消息内容填充内容");
    }

    @Test
    @DisplayName("reserveLatestCount 保证至少保留最近 N 条")
    void reserveLatest_shouldKeepRecentMessages() {
        // 预算 20 token，但强制保留最近 2 条（即使可能超预算）
        List<ChatMessage> history = List.of(
                message("旧消息内容非常长超过预算会被优先裁剪"),
                message("旧消息内容非常长超过预算会被优先裁剪"),
                message("新消息1"),
                message("新消息2"));

        List<ChatMessage> result = manager.trimHistory(history, 2);

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.get(result.size() - 1).getContent()).isEqualTo("新消息2");
        assertThat(result.get(result.size() - 2).getContent()).isEqualTo("新消息1");
    }

    @Test
    @DisplayName("消息数不超过保留下限：全部保留")
    void sizeBelowReserve_shouldKeepAll() {
        List<ChatMessage> history = List.of(
                message("消息A"),
                message("消息B"));

        List<ChatMessage> result = manager.trimHistory(history, 3);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("空历史：返回空列表")
    void emptyHistory_shouldReturnEmpty() {
        assertThat(manager.trimHistory(List.of(), 1)).isEmpty();
        assertThat(manager.trimHistory(null, 1)).isEmpty();
    }

    @Test
    @DisplayName("超长单条消息：裁剪到至少保留 1 条")
    void extremelyLongMessage_shouldStillKeepAtLeastOne() {
        String longMessage = "很长的内容".repeat(500);
        List<ChatMessage> history = new ArrayList<>();
        history.add(message(longMessage));
        history.add(message("短消息"));

        List<ChatMessage> result = manager.trimHistory(history, 1);

        assertThat(result).hasSize(1);
    }

    private ChatMessage message(String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent(content);
        return message;
    }
}
