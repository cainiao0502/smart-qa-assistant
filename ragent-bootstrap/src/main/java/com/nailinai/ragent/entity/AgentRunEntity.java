package com.nailinai.ragent.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRunEntity extends BaseEntity {

    private String runId;
    private String sessionId;
    private Long kbId;
    private String userGoal;
    private String status;
    private String finalAnswer;
}
