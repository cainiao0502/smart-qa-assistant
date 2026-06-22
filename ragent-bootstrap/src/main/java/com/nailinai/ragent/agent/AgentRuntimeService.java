package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentRuntimeService {

    private final AgentPlannerService agentPlannerService;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final AgentRunStore agentRunStore;
    private final int maxSteps;

    public AgentRuntimeService(AgentPlannerService agentPlannerService,
                               ToolExecutorRegistry toolExecutorRegistry,
                               AgentRunStore agentRunStore,
                               @Value("${app.agent.max-steps:6}") int maxSteps) {
        this.agentPlannerService = agentPlannerService;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.agentRunStore = agentRunStore;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public AgentRuntimeResult run(ChatRequest request, List<ChatMessage> history, RetrievalResult retrievalResult) {
        return run(request, history, retrievalResult, AgentRuntimeListener.NOOP);
    }

    public AgentRuntimeResult run(ChatRequest request,
                                  List<ChatMessage> history,
                                  RetrievalResult retrievalResult,
                                  AgentRuntimeListener listener) {
        LocalDateTime now = LocalDateTime.now();
        AgentRun.AgentRunBuilder runBuilder = AgentRun.builder()
                .runId("run_" + UUID.randomUUID().toString().replace("-", ""))
                .sessionId(request.getSessionId())
                .kbId(request.getKbId())
                .userGoal(request.getQuestion())
                .status("RUNNING")
                .createdAt(now)
                .updatedAt(now);

        AgentRun initialRun = runBuilder.build();
        agentRunStore.createRun(initialRun);
        listener.onRunCreated(initialRun);

        List<AgentStep> steps = new ArrayList<>();
        List<AgentPlanItem> plan = List.of();
        List<ToolCallTraceResponse> toolCalls = new ArrayList<>();
        List<ReferenceChunkResponse> extraReferences = new ArrayList<>();
        List<String> supplementalContexts = new ArrayList<>();

        String answerMode = "rag";
        String finalInstruction = null;
        String terminalStatus = "SUCCESS";
        String currentActionKey = null;
        Set<String> completedTaskKeys = new LinkedHashSet<>();

        for (int stepIndex = 1; stepIndex <= maxSteps; stepIndex++) {
            PlannerDecision decision = decideNextAction(
                    request,
                    history,
                    retrievalResult,
                    steps,
                    plan,
                    completedTaskKeys
            );
            if (decision == null) {
                break;
            }

            String previousActionKey = currentActionKey;
            if (StringUtils.hasText(previousActionKey)
                    && StringUtils.hasText(decision.getTaskKey())
                    && !Objects.equals(previousActionKey, decision.getTaskKey())
                    && hasSuccessfulTaskActivity(steps, previousActionKey)) {
                completedTaskKeys.add(previousActionKey);
            }

            if (!decision.getPlan().isEmpty() && !samePlan(plan, decision.getPlan())) {
                plan = decision.getPlan();
                currentActionKey = decision.getTaskKey();
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey, List.copyOf(completedTaskKeys));

                AgentStep planStep = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(0)
                        .stepType("plan")
                        .arguments(Map.of(
                                "tasks", plan,
                                "currentActionKey", currentActionKey == null ? "" : currentActionKey
                        ))
                        .reason(decision.getReason())
                        .observationSummary("Planner generated an execution plan.")
                        .status("SUCCESS")
                        .durationMs(0L)
                        .build();
                steps.add(planStep);
                agentRunStore.appendStep(planStep);
                listener.onStepCompleted(planStep);
            } else if (StringUtils.hasText(decision.getTaskKey())) {
                currentActionKey = decision.getTaskKey();
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey, List.copyOf(completedTaskKeys));
            }

            if (decision.getCurrentAction() != null && (decision.shouldFinish() || decision.shouldRespondWithGap())) {
                String normalizedTerminalTaskKey = resolveTerminalTaskKey(plan, decision.getTaskKey());
                decision.getCurrentAction().setTaskKey(normalizedTerminalTaskKey);
                currentActionKey = normalizedTerminalTaskKey;
            }

            if (decision.shouldFinish()) {
                finalInstruction = decision.getResponse();
                markTaskCompleted(completedTaskKeys, currentActionKey);
                AgentStep step = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(stepIndex)
                        .stepType("finish")
                        .arguments(Map.of("taskKey", currentActionKey == null ? "" : currentActionKey))
                        .reason(decision.getReason())
                        .observationSummary(summaryFrom(decision.getResponse(), "Planner decided to finish with current context."))
                        .status("SUCCESS")
                        .durationMs(0L)
                        .build();
                steps.add(step);
                agentRunStore.appendStep(step);
                listener.onStepCompleted(step);
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey, List.copyOf(completedTaskKeys));
                break;
            }

            if (decision.shouldRespondWithGap()) {
                finalInstruction = decision.getResponse();
                answerMode = "respond_with_gap";
                markTaskCompleted(completedTaskKeys, currentActionKey);
                AgentStep step = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(stepIndex)
                        .stepType("respond_with_gap")
                        .arguments(Map.of("taskKey", currentActionKey == null ? "" : currentActionKey))
                        .reason(decision.getReason())
                        .observationSummary(summaryFrom(decision.getResponse(), "Planner identified an information gap."))
                        .status("SUCCESS")
                        .durationMs(0L)
                        .build();
                steps.add(step);
                agentRunStore.appendStep(step);
                listener.onStepCompleted(step);
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey, List.copyOf(completedTaskKeys));
                break;
            }

            if (!decision.shouldCallTool()) {
                break;
            }

            Map<String, Object> normalizedArguments = decision.getArguments() == null
                    ? Map.of()
                    : new LinkedHashMap<>(decision.getArguments());

            ToolExecutor executor = toolExecutorRegistry.getRequired(decision.getTool());
            long startTime = System.currentTimeMillis();
            ToolExecutionResult toolResult;
            String status = "SUCCESS";
            String observationSummary;
            ToolCallTraceResponse toolTrace = null;

            AgentStep runningStep = AgentStep.builder()
                    .runId(initialRun.getRunId())
                    .stepIndex(stepIndex)
                    .stepType("tool_call")
                    .toolName(executor.getToolName())
                    .arguments(withTaskKey(normalizedArguments, currentActionKey))
                    .reason(decision.getReason())
                    .status("RUNNING")
                    .durationMs(0L)
                    .build();
            listener.onStepStarted(runningStep);

            try {
                toolResult = executor.execute(
                        normalizedArguments,
                        ToolContext.builder()
                                .request(request)
                                .history(history)
                                .retrievedChunks(retrievalResult.getChunks())
                                .build()
                );
                observationSummary = summaryFrom(toolResult.getObservation(), toolResult.getSummary());
                if (toolResult.getTrace() != null) {
                    toolTrace = toolResult.getTrace();
                    toolCalls.add(toolTrace);
                    status = StringUtils.hasText(toolTrace.getStatus())
                            ? toolTrace.getStatus()
                            : status;
                }
                if (toolResult.getReferences() != null && !toolResult.getReferences().isEmpty()) {
                    extraReferences.addAll(toolResult.getReferences());
                }
                if (StringUtils.hasText(toolResult.getSupplementalContext())) {
                    supplementalContexts.add(toolResult.getSupplementalContext().trim());
                    answerMode = "rag+tool";
                }
            } catch (Exception exception) {
                status = "FAILED";
                observationSummary = summaryFrom(exception.getMessage(), "Tool execution failed.");
                terminalStatus = "PARTIAL";
                toolTrace = ToolCallTraceResponse.builder()
                        .toolName(executor.getToolName())
                        .displayName(executor.getDisplayName())
                        .source(executor.getSource())
                        .status("FAILED")
                        .arguments(normalizedArguments)
                        .summary(observationSummary)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
                toolCalls.add(toolTrace);
            }

            if (toolTrace != null) {
                listener.onToolResult(toolTrace);
            }

            AgentStep step = AgentStep.builder()
                    .runId(initialRun.getRunId())
                    .stepIndex(stepIndex)
                    .stepType("tool_call")
                    .toolName(executor.getToolName())
                    .arguments(withTaskKey(normalizedArguments, currentActionKey))
                    .reason(decision.getReason())
                    .observationSummary(observationSummary)
                    .status(status)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
            steps.add(step);
            agentRunStore.appendStep(step);
            listener.onStepCompleted(step);
            listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey, List.copyOf(completedTaskKeys));
        }

        long executedActionCount = steps.stream()
                .filter(step -> !"plan".equalsIgnoreCase(step.getStepType()))
                .count();
        if (executedActionCount >= maxSteps && steps.stream().noneMatch(step -> "finish".equalsIgnoreCase(step.getStepType()) || "respond_with_gap".equalsIgnoreCase(step.getStepType()))) {
            finalInstruction = summaryFrom(finalInstruction, "Agent reached the max step limit. Summarize what is known and state remaining uncertainty.");
            terminalStatus = "PARTIAL";
        }

        List<ReferenceChunkResponse> references = mergeReferences(retrievalResult.getChunks(), extraReferences);

        AgentRun run = runBuilder
                .status(terminalStatus)
                .updatedAt(LocalDateTime.now())
                .build();

        return AgentRuntimeResult.builder()
                .run(run)
                .plan(plan)
                .currentActionKey(currentActionKey)
                .completedTaskKeys(List.copyOf(completedTaskKeys))
                .steps(steps)
                .toolCalls(toolCalls)
                .references(references)
                .retrievalResult(retrievalResult)
                .supplementalContexts(List.copyOf(supplementalContexts))
                .finalInstruction(finalInstruction)
                .answerMode(answerMode)
                .build();
    }

    private Map<String, Object> withTaskKey(Map<String, Object> arguments, String taskKey) {
        if (!StringUtils.hasText(taskKey)) {
            return arguments;
        }
        Map<String, Object> result = new LinkedHashMap<>(arguments);
        result.put("taskKey", taskKey);
        return result;
    }

    private PlannerDecision decideNextAction(ChatRequest request,
                                             List<ChatMessage> history,
                                             RetrievalResult retrievalResult,
                                             List<AgentStep> steps,
                                             List<AgentPlanItem> existingPlan,
                                             Set<String> completedTaskKeys) {
        List<AgentStep> workingSteps = new ArrayList<>(steps);
        PlannerDecision lastDecision = null;
        List<AgentPlanItem> lastActivePlan = existingPlan;
        for (int guardAttempt = 0; guardAttempt < 2; guardAttempt++) {
            PlannerDecision decision = agentPlannerService.decide(request, history, retrievalResult.getChunks(), workingSteps);
            lastDecision = decision;
            if (decision == null) {
                return null;
            }
            List<AgentPlanItem> activePlan = decision.getPlan().isEmpty() ? existingPlan : decision.getPlan();
            lastActivePlan = activePlan;
            if (!isPrematureTerminalDecision(decision, activePlan, completedTaskKeys)) {
                return decision;
            }
            workingSteps.add(buildPlannerGuardStep(workingSteps, activePlan, completedTaskKeys));
        }
        return buildForcedContinuationDecision(lastDecision, lastActivePlan, completedTaskKeys, steps);
    }

    private boolean isPrematureTerminalDecision(PlannerDecision decision,
                                                List<AgentPlanItem> activePlan,
                                                Set<String> completedTaskKeys) {
        if (decision == null || activePlan == null || activePlan.size() <= 1) {
            return false;
        }
        if (!decision.shouldFinish() && !decision.shouldRespondWithGap()) {
            return false;
        }
        for (int index = 0; index < activePlan.size() - 1; index++) {
            AgentPlanItem task = activePlan.get(index);
            if (task != null && StringUtils.hasText(task.getKey()) && !completedTaskKeys.contains(task.getKey())) {
                return true;
            }
        }
        return false;
    }

    private String resolveTerminalTaskKey(List<AgentPlanItem> activePlan, String fallbackTaskKey) {
        if (activePlan == null || activePlan.isEmpty()) {
            return fallbackTaskKey;
        }
        String finalTaskKey = activePlan.get(activePlan.size() - 1).getKey();
        return StringUtils.hasText(finalTaskKey) ? finalTaskKey : fallbackTaskKey;
    }

    private AgentStep buildPlannerGuardStep(List<AgentStep> steps,
                                            List<AgentPlanItem> activePlan,
                                            Set<String> completedTaskKeys) {
        List<String> blockingTaskKeys = activePlan.stream()
                .limit(Math.max(0, activePlan.size() - 1L))
                .filter(task -> task != null && StringUtils.hasText(task.getKey()) && !completedTaskKeys.contains(task.getKey()))
                .map(AgentPlanItem::getKey)
                .toList();
        int nextIndex = steps.stream()
                .map(AgentStep::getStepIndex)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return AgentStep.builder()
                .stepIndex(nextIndex)
                .stepType("planner_guard")
                .arguments(Map.of("blockingTaskKeys", blockingTaskKeys))
                .reason("Planner attempted to finish early while non-final tasks remained unfinished.")
                .observationSummary("Finish was blocked because unfinished tasks remain: " + String.join(", ", blockingTaskKeys))
                .status("BLOCKED")
                .durationMs(0L)
                .build();
    }

    private PlannerDecision buildForcedContinuationDecision(PlannerDecision lastDecision,
                                                            List<AgentPlanItem> activePlan,
                                                            Set<String> completedTaskKeys,
                                                            List<AgentStep> steps) {
        AgentPlanItem unfinishedTask = findEarliestUnfinishedNonFinalTask(activePlan, completedTaskKeys);
        if (unfinishedTask == null) {
            return lastDecision;
        }

        String inferredTool = inferContinuationTool(steps);
        if (StringUtils.hasText(inferredTool)) {
            PlannerDecision forced = new PlannerDecision();
            forced.setPlan(activePlan == null ? List.of() : List.copyOf(activePlan));
            com.nailinai.ragent.agent.dto.PlannerCurrentAction action = new com.nailinai.ragent.agent.dto.PlannerCurrentAction();
            action.setTaskKey(unfinishedTask.getKey());
            action.setAction("tool_call");
            action.setTool(inferredTool);
            action.setArguments(buildForcedToolArguments(inferredTool, unfinishedTask));
            action.setReason("Forced continuation because the planner attempted to finish before all non-final tasks were executed.");
            forced.setCurrentAction(action);
            return forced;
        }

        PlannerDecision fallback = new PlannerDecision();
        fallback.setPlan(activePlan == null ? List.of() : List.copyOf(activePlan));
        com.nailinai.ragent.agent.dto.PlannerCurrentAction action = new com.nailinai.ragent.agent.dto.PlannerCurrentAction();
        action.setTaskKey(unfinishedTask.getKey());
        action.setAction("respond_with_gap");
        action.setResponse("还有计划中的任务尚未执行，当前结果不足以完整收尾。");
        action.setReason("Planner repeatedly tried to finish before executing all non-final tasks.");
        fallback.setCurrentAction(action);
        return fallback;
    }

    private AgentPlanItem findEarliestUnfinishedNonFinalTask(List<AgentPlanItem> activePlan,
                                                             Set<String> completedTaskKeys) {
        if (activePlan == null || activePlan.size() <= 1) {
            return null;
        }
        for (int index = 0; index < activePlan.size() - 1; index++) {
            AgentPlanItem task = activePlan.get(index);
            if (task != null && StringUtils.hasText(task.getKey()) && !completedTaskKeys.contains(task.getKey())) {
                return task;
            }
        }
        return null;
    }

    private String inferContinuationTool(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            AgentStep step = steps.get(index);
            if (step == null
                    || !"tool_call".equalsIgnoreCase(step.getStepType())
                    || !"SUCCESS".equalsIgnoreCase(step.getStatus())
                    || !StringUtils.hasText(step.getToolName())) {
                continue;
            }
            return step.getToolName();
        }
        return null;
    }

    private Map<String, Object> buildForcedToolArguments(String toolName, AgentPlanItem unfinishedTask) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        String taskText = ((unfinishedTask.getTitle() == null ? "" : unfinishedTask.getTitle()) + " "
                + (unfinishedTask.getDescription() == null ? "" : unfinishedTask.getDescription())).trim();
        if (toolName != null && toolName.toLowerCase().contains("search") && StringUtils.hasText(taskText)) {
            arguments.put("query", taskText);
        }
        return arguments;
    }

    private boolean samePlan(List<AgentPlanItem> left, List<AgentPlanItem> right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            AgentPlanItem leftItem = left.get(index);
            AgentPlanItem rightItem = right.get(index);
            if (!Objects.equals(leftItem.getKey(), rightItem.getKey())
                    || !Objects.equals(leftItem.getTitle(), rightItem.getTitle())
                    || !Objects.equals(leftItem.getDescription(), rightItem.getDescription())) {
                return false;
            }
        }
        return true;
    }

    private void markTaskCompleted(Set<String> completedTaskKeys, String taskKey) {
        if (StringUtils.hasText(taskKey)) {
            completedTaskKeys.add(taskKey);
        }
    }

    private boolean hasSuccessfulTaskActivity(List<AgentStep> steps, String taskKey) {
        if (!StringUtils.hasText(taskKey) || steps == null || steps.isEmpty()) {
            return false;
        }
        return steps.stream().anyMatch(step -> taskKey.equals(step.getArguments() == null ? null : step.getArguments().get("taskKey"))
                && !"FAILED".equalsIgnoreCase(step.getStatus())
                && !"plan".equalsIgnoreCase(step.getStepType()));
    }

    private List<ReferenceChunkResponse> mergeReferences(List<DocumentChunk> baseChunks,
                                                         List<ReferenceChunkResponse> extraReferences) {
        Map<String, ReferenceChunkResponse> merged = new LinkedHashMap<>();
        for (DocumentChunk chunk : baseChunks) {
            ReferenceChunkResponse reference = ReferenceChunkResponse.builder()
                    .docId(chunk.getDocId())
                    .documentName(chunk.getDocumentName())
                    .fileType(chunk.getFileType())
                    .chunkIndex(chunk.getChunkIndex())
                    .paragraphIndex(chunk.getParagraphIndex())
                    .chunkText(chunk.getChunkText())
                    .score(chunk.getScore())
                    .distance(chunk.getDistance())
                    .rerankScore(chunk.getRerankScore())
                    .hitReason(chunk.getHitReason())
                    .build();
            merged.put(referenceKey(reference), reference);
        }
        for (ReferenceChunkResponse reference : extraReferences) {
            merged.putIfAbsent(referenceKey(reference), reference);
        }
        return new ArrayList<>(merged.values());
    }

    private String referenceKey(ReferenceChunkResponse reference) {
        return "%s:%s".formatted(reference.getDocId(), reference.getChunkIndex());
    }

    private String summaryFrom(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }
}
