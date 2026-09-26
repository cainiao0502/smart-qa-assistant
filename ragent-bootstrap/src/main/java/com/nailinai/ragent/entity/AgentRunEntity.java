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
    /** 归属用户：run 详情接口用它做归属校验（历史行为 NULL，回退会话消息校验） */
    private Long ownerUserId;

    // ---- 成本与耗时统计（口径参考 pi 的 telemetry：只记 provider 上报值）----
    /** 主循环耗时（毫秒） */
    private Long durationMs;
    /** 主循环内 LLM 调用次数 */
    private Integer llmCalls;
    /** 累计输入 token（含缓存命中） */
    private Integer inputTokens;
    /** 累计输出 token */
    private Integer outputTokens;
    /** 输入中命中 prompt 缓存的 token（折扣价计费） */
    private Integer cachedTokens;
    /** 输出中属于思考的 token */
    private Integer reasoningTokens;
}
