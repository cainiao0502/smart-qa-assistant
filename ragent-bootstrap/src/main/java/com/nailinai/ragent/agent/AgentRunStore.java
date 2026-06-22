package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.entity.AgentRunEntity;
import com.nailinai.ragent.entity.AgentStepEntity;
import com.nailinai.ragent.mapper.AgentRunMapper;
import com.nailinai.ragent.mapper.AgentStepMapper;
import com.nailinai.ragent.framework.util.JsonUtils;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentRunStore {

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
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setStatus(status);
        entity.setFinalAnswer(finalAnswer);
        agentRunMapper.updateResult(entity);
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
