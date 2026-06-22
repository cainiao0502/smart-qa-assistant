package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.agent.AgentStepHistoryService;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.enums.MessageRole;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.chat.service.MemoryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class MemoryServiceImpl implements MemoryService {
    private final ChatMessageMapper chatMessageMapper;
    private final AgentStepHistoryService agentStepHistoryService;

    public MemoryServiceImpl(ChatMessageMapper chatMessageMapper,
                             AgentStepHistoryService agentStepHistoryService) {
        this.chatMessageMapper = chatMessageMapper;
        this.agentStepHistoryService = agentStepHistoryService;
    }

    @Override
    public void saveUserMessage(String sessionId, Long kbId, String content) {
        save(sessionId, kbId, null, MessageRole.USER, content, null, null);
    }

    @Override
    public void saveAssistantMessage(String sessionId, Long kbId, String runId, String content, String referencesJson, String toolCallsJson) {
        save(sessionId, kbId, runId, MessageRole.ASSISTANT, content, referencesJson, toolCallsJson);
    }

    @Override
    public List<ChatMessage> getRecentMessages(String sessionId, int limit) {
        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        int size = messages.size();
        return messages.stream()
                .skip(Math.max(0, size - limit))
                .toList();
    }

    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return enrichAgentSteps(chatMessageMapper.selectBySessionId(sessionId));
    }

    private void save(String sessionId, Long kbId, String runId, MessageRole role, String content, String referencesJson, String toolCallsJson) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setKbId(kbId);
        message.setRunId(runId);
        message.setRole(role);
        message.setContent(content);
        message.setReferencesJson(referencesJson);
        message.setToolCallsJson(toolCallsJson);
        chatMessageMapper.insert(message);
    }

    private List<ChatMessage> enrichAgentSteps(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message.getRole() == MessageRole.ASSISTANT && StringUtils.hasText(message.getRunId())) {
                message.setAgentSteps(agentStepHistoryService.getSteps(message.getRunId()));
            }
        }
        return messages;
    }
}
