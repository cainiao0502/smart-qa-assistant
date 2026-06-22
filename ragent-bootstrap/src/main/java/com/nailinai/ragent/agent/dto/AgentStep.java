package com.nailinai.ragent.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AgentStep {

    private String runId;
    private Integer stepIndex;
    private String stepType;
    private String toolName;
    private Map<String, Object> arguments;
    private String reason;
    private String observationSummary;
    private String status;
    private Long durationMs;
}
