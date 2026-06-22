package com.nailinai.ragent.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentStepEntity extends BaseEntity {

    private String runId;
    private Integer stepIndex;
    private String stepType;
    private String toolName;
    private String argumentsJson;
    private String reason;
    private String observationSummary;
    private String status;
    private Long durationMs;
}
