package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.RunUsage;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.entity.AgentRunEntity;
import com.nailinai.ragent.entity.AgentStepEntity;
import com.nailinai.ragent.mapper.AgentRunMapper;
import com.nailinai.ragent.mapper.AgentStepMapper;
import com.nailinai.ragent.framework.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentRunStore {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStore.class);

    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final AgentStepHistoryService agentStepHistoryService;

    public AgentRunStore(AgentRunMapper agentRunMapper,
                         AgentStepMapper agentStepMapper,
                         AgentStepHistoryService agentStepHistoryService) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepMapper = agentStepMapper;
        this.agentStepHistoryService = agentStepHistoryService;
    }

    public void createRun(AgentRun run) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(run.getRunId());
        entity.setSessionId(run.getSessionId());
        entity.setKbId(run.getKbId());
        entity.setUserGoal(run.getUserGoal());
        entity.setStatus(run.getStatus());
        entity.setFinalAnswer(run.getFinalAnswer());
        agentRunMapper.insert(entity);
    }

    public void appendStep(AgentStep step) {
        AgentStepEntity entity = new AgentStepEntity();
        entity.setRunId(step.getRunId());
        entity.setStepIndex(step.getStepIndex());
        entity.setStepType(step.getStepType());
        entity.setToolName(step.getToolName());
        entity.setArgumentsJson(toJsonObject(step.getArguments()));
        entity.setReason(step.getReason());
        entity.setObservationSummary(step.getObservationSummary());
        entity.setStatus(step.getStatus());
        entity.setDurationMs(step.getDurationMs());
        agentStepMapper.insert(entity);
    }

    public void completeRun(String runId, String status, String finalAnswer) {
        completeRun(runId, status, finalAnswer, null);
    }

    /**
     * 完成运行并落库成本/耗时统计。
     *
     * <p>统计信息与状态写在同一条 UPDATE 里，避免"状态已结束但统计还没写"的中间态；
     * usage 为 null 时（异常路径）对应列保持原值。
     */
    public void completeRun(String runId, String status, String finalAnswer, RunUsage usage) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setStatus(status);
        entity.setFinalAnswer(finalAnswer);
        if (usage != null) {
            entity.setDurationMs(usage.loopDurationMs());
            entity.setLlmCalls(usage.llmCalls());
            entity.setInputTokens(usage.inputTokens());
            entity.setOutputTokens(usage.outputTokens());
            entity.setCachedTokens(usage.cachedTokens());
            entity.setReasoningTokens(usage.reasoningTokens());
        }
        int updated = agentRunMapper.updateResult(entity);
        if (updated == 0) {
            log.warn("completeRun: no row updated for runId={}", runId);
        }
    }

    public AgentRunDetailResponse getRunDetail(String runId) {
        if (!StringUtils.hasText(runId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        AgentRunEntity run = agentRunMapper.selectByRunId(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "agent run not found");
        }
        List<AgentStep> steps = agentStepHistoryService.getSteps(runId);
        List<com.nailinai.ragent.agent.dto.AgentPlanItem> plan = agentStepHistoryService.extractLatestPlan(steps);
        String currentActionKey = agentStepHistoryService.extractCurrentActionKey(steps);
        List<String> completedTaskKeys = agentStepHistoryService.extractCompletedTaskKeys(steps);
        return AgentRunDetailResponse.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .kbId(run.getKbId())
                .userGoal(run.getUserGoal())
                .status(run.getStatus())
                .finalAnswer(run.getFinalAnswer())
                .agentPlan(plan)
                .currentActionKey(currentActionKey)
                .completedTaskKeys(completedTaskKeys)
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .steps(steps)
                .build();
    }

    private String toJsonObject(Map<String, Object> arguments) {
        return arguments == null ? "{}" : JsonUtils.toJson(arguments);
    }
}
