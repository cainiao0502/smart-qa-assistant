package com.nailinai.ragent.entity;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.enums.MessageRole;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatMessage {

    private Long id;
    private String sessionId;
    private Long kbId;
    private String runId;
    private MessageRole role;
    private String content;
    private String referencesJson;
    private String toolCallsJson;
    private Long ownerUserId;
    private List<AgentStep> agentSteps;
    private LocalDateTime createdAt;
}
