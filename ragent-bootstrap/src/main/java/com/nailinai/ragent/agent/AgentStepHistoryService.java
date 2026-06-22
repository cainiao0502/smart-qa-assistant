package com.nailinai.ragent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.entity.AgentStepEntity;
import com.nailinai.ragent.mapper.AgentStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentStepHistoryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<AgentPlanItem>> PLAN_TYPE = new TypeReference<>() {};

    private final AgentStepMapper agentStepMapper;
    private final ObjectMapper objectMapper;

    public AgentStepHistoryService(AgentStepMapper agentStepMapper, ObjectMapper objectMapper) {
        this.agentStepMapper = agentStepMapper;
        this.objectMapper = objectMapper;
    }

    public List<AgentStep> getSteps(String runId) {
        return agentStepMapper.selectByRunId(runId).stream()
                .map(this::toDto)
                .toList();
    }

    public List<AgentPlanItem> extractLatestPlan(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            AgentStep step = steps.get(index);
            if (!"plan".equalsIgnoreCase(step.getStepType())) {
                continue;
            }
            Object rawTasks = step.getArguments() == null ? null : step.getArguments().get("tasks");
            if (rawTasks == null) {
                continue;
            }
            try {
                return objectMapper.convertValue(rawTasks, PLAN_TYPE);
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    public String extractCurrentActionKey(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            AgentStep step = steps.get(index);
            if (step.getArguments() == null) {
                continue;
            }
            Object rawTaskKey = step.getArguments().get("taskKey");
            if (rawTaskKey instanceof String taskKey && StringUtils.hasText(taskKey)) {
                return taskKey;
            }
            if ("plan".equalsIgnoreCase(step.getStepType())) {
                Object rawCurrentActionKey = step.getArguments().get("currentActionKey");
                if (rawCurrentActionKey instanceof String currentActionKey && StringUtils.hasText(currentActionKey)) {
                    return currentActionKey;
                }
            }
        }
        return null;
    }

    public List<String> extractCompletedTaskKeys(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<AgentStep> sortedSteps = sortSteps(steps);
        Set<String> completedTaskKeys = new LinkedHashSet<>();
        String activeTaskKey = null;
        boolean activeTaskHasSuccess = false;

        for (AgentStep step : sortedSteps) {
            if (step == null || step.getArguments() == null) {
                continue;
            }
            Object rawTaskKey = step.getArguments().get("taskKey");
            if (!(rawTaskKey instanceof String taskKey) || !StringUtils.hasText(taskKey)) {
                continue;
            }
            if (StringUtils.hasText(activeTaskKey) && !activeTaskKey.equals(taskKey) && activeTaskHasSuccess) {
                completedTaskKeys.add(activeTaskKey);
            }
            if (!taskKey.equals(activeTaskKey)) {
                activeTaskKey = taskKey;
                activeTaskHasSuccess = false;
            }
            if (!"FAILED".equalsIgnoreCase(step.getStatus()) && !"plan".equalsIgnoreCase(step.getStepType())) {
                activeTaskHasSuccess = true;
            }
            if ("finish".equalsIgnoreCase(step.getStepType()) || "respond_with_gap".equalsIgnoreCase(step.getStepType())) {
                completedTaskKeys.add(taskKey);
            }
        }
        return List.copyOf(completedTaskKeys);
    }

    private List<AgentStep> sortSteps(List<AgentStep> steps) {
        return steps.stream()
                .sorted((left, right) -> Integer.compare(
                        left == null || left.getStepIndex() == null ? 0 : left.getStepIndex(),
                        right == null || right.getStepIndex() == null ? 0 : right.getStepIndex()
                ))
                .toList();
    }

    private AgentStep toDto(AgentStepEntity entity) {
        return AgentStep.builder()
                .runId(entity.getRunId())
                .stepIndex(entity.getStepIndex())
                .stepType(entity.getStepType())
                .toolName(entity.getToolName())
                .arguments(parseArguments(entity.getArgumentsJson()))
                .reason(entity.getReason())
                .observationSummary(entity.getObservationSummary())
                .status(entity.getStatus())
                .durationMs(entity.getDurationMs())
                .build();
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
