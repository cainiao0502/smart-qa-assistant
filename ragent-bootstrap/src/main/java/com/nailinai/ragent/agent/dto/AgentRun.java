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
    /** 归属用户（创建 run 时从 UserIdHolder 读取），run 详情接口据此校验归属 */
    private Long ownerUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
