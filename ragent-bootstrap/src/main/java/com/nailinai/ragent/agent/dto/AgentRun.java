package com.nailinai.ragent.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AgentRun {

    private String runId;
    private String sessionId;
    private Long kbId;
    private String userGoal;
    private String status;
    private String finalAnswer;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
