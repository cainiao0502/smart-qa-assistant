package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AgentRunDetailResponse {

    private String runId;
    private String sessionId;
    private Long kbId;
    private String userGoal;
    private String status;
    private String finalAnswer;
    private List<AgentPlanItem> agentPlan;
    private String currentActionKey;
    private List<String> completedTaskKeys;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<AgentStep> steps;
}
