package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.agent.AgentStepHistoryService;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.enums.MessageRole;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.chat.service.MemoryService;
import com.nailinai.ragent.user.context.UserIdHolder;
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
        List<ChatMessage> messages = chatMessageMapper.selectRecentBySessionId(sessionId, limit);
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return enrichAgentSteps(chatMessageMapper.selectBySessionId(sessionId));
    }

    private void save(String sessionId, Long kbId, String runId, MessageRole role, String content, String referencesJson, String toolCallsJson) {
        Long userId = resolveCurrentUserId();
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setKbId(kbId);
        message.setRunId(runId);
        message.setRole(role);
        message.setContent(content);
        message.setReferencesJson(referencesJson);
        message.setToolCallsJson(toolCallsJson);
        message.setOwnerUserId(userId);
        chatMessageMapper.insert(message);
    }

    /**
     * 解析消息所属用户。
     *
     * <p>走 {@link UserIdHolder} 而非直接读 Sa-Token 上下文：流式对话
     * （{@code /api/chat/stream}）在 ForkJoinPool 线程里执行，那里读不到
     * Sa-Token 的 ThreadLocal，会导致 owner_user_id 落库为 NULL、
     * 进而使历史会话列表查询不到（详见 {@link UserIdHolder} 类注释）。</p>
     */
    private static Long resolveCurrentUserId() {
        return UserIdHolder.get();
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
