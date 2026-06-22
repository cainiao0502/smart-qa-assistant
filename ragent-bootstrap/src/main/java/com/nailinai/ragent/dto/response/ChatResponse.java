package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ChatResponse {

    private String runId;
    private String runStatus;
    private String answer;
    private String answerMode;
    private Map<String, Object> retrievalConfig;
    private List<ReferenceChunkResponse> references;
    private List<ToolCallTraceResponse> toolCalls;
    private List<AgentPlanItem> agentPlan;
    private String currentActionKey;
    private List<String> completedTaskKeys;
    private List<AgentStep> agentSteps;
}
