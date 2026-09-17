package com.nailinai.ragent.agent.dto;

import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentRuntimeResult {

    private AgentRun run;
    private List<AgentPlanItem> plan;
    private String currentActionKey;
    private List<String> completedTaskKeys;
    private List<AgentStep> steps;
    private List<ToolCallTraceResponse> toolCalls;
    private List<ReferenceChunkResponse> references;
    private RetrievalResult retrievalResult;
    private List<String> supplementalContexts;
    private String finalInstruction;
    private String answerMode;

    /** 本轮运行的成本与耗时统计（落库 / SSE 下发 / 日志共用同一份数据） */
    private RunUsage usage;
}
