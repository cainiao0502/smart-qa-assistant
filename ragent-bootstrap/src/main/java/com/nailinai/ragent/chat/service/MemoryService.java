package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.entity.ChatMessage;

import java.util.List;

public interface MemoryService {

    void saveUserMessage(String sessionId, Long kbId, String content);

    void saveAssistantMessage(String sessionId, Long kbId, String runId, String content, String referencesJson, String toolCallsJson);

    List<ChatMessage> getRecentMessages(String sessionId, int limit);

    List<ChatMessage> getSessionMessages(String sessionId);
}
