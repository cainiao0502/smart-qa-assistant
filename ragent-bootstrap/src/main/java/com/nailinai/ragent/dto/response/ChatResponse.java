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
    private String clarification;
    private Map<String, Object> retrievalConfig;
    private List<ReferenceChunkResponse> references;
    private List<ToolCallTraceResponse> toolCalls;
    private List<AgentPlanItem> agentPlan;
    private String currentActionKey;
    private List<String> completedTaskKeys;
    private List<AgentStep> agentSteps;
    /** 本轮运行的成本与耗时（LLM 调用次数 / token 用量 / 循环耗时），用于运行详情展示 */
    private com.nailinai.ragent.agent.dto.RunUsage usage;
}
