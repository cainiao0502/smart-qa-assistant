package com.nailinai.ragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.agent.dto.PlannerCurrentAction;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.skill.SkillRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AgentPlannerService {
    private static final Pattern CATALOG_DOCUMENT_PATTERN = Pattern.compile("#(\\d+)\\s+([^;()]+?)\\s+\\(");

    private final ChatClient chatClient;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final ObjectMapper objectMapper;
    private final SkillRegistry skillRegistry;

    public AgentPlannerService(ChatClient chatClient,
                               ToolExecutorRegistry toolExecutorRegistry,
                               ObjectMapper objectMapper,
                               SkillRegistry skillRegistry) {
        this.chatClient = chatClient;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.objectMapper = objectMapper;
        this.skillRegistry = skillRegistry;
    }

    public PlannerDecision decide(ChatRequest request,
                                  List<ChatMessage> history,
                                  List<DocumentChunk> retrievedChunks,
                                  List<AgentStep> priorSteps) {
        String prompt = buildPlanningPrompt(request, history, retrievedChunks, priorSteps);

        // 第一次尝试
        PlannerDecision decision = parseLlmResponse(prompt, priorSteps, false);
        if (decision != null) {
            return decision;
        }

        // 第二次尝试（重试一次）
        return parseLlmResponse(prompt, priorSteps, true);
    }

    private PlannerDecision parseLlmResponse(String prompt, List<AgentStep> priorSteps, boolean isRetry) {
        String response = chatClient.chat(prompt);
        if (!StringUtils.hasText(response)) {
            return defaultFinishDecision(isRetry
                    ? "Planner retry returned empty response."
                    : "Planner returned empty response.");
        }

        try {
            PlannerDecision decision = objectMapper.readValue(extractJsonObject(response), PlannerDecision.class);
            if (!StringUtils.hasText(decision.getAction())) {
                return defaultFinishDecision("Planner returned decision without action.");
            }
            List<AgentPlanItem> existingPlan = extractLatestPlan(priorSteps);
            Set<String> completedTaskKeys = extractCompletedTaskKeys(priorSteps);
            decision.setPlan(stabilizePlan(normalizePlan(decision.getPlan()), existingPlan));
            if (decision.getCurrentAction() != null && !StringUtils.hasText(decision.getCurrentAction().getTaskKey())) {
                String fallbackTaskKey = resolveFallbackTaskKey(decision, completedTaskKeys);
                decision.getCurrentAction().setTaskKey(fallbackTaskKey);
            }
            repairToolArguments(decision, priorSteps);
            return decision;
        } catch (Exception exception) {
            if (isRetry) {
                // 第二次失败，不再重试
                return defaultFinishDecision("Planner response could not be parsed after retry. Use current context to answer.");
            }
            // 第一次失败，返回 null 以触发重试
            return null;
        }
    }

    private String buildPlanningPrompt(ChatRequest request,
                                       List<ChatMessage> history,
                                       List<DocumentChunk> chunks,
                                       List<AgentStep> priorSteps) {
        LocalDate today = LocalDate.now();
        String historyText = history.stream()
                .skip(Math.max(0, history.size() - 4L))
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        String contextText = chunks.stream()
                .limit(4)
                .map(chunk -> {
                    String source = chunk.getDocumentName() == null ? "unknown" : chunk.getDocumentName();
                    return "[%s#%s] %s".formatted(source, chunk.getChunkIndex(), chunk.getChunkText());
                })
                .collect(Collectors.joining("\n\n"));
        String stepText = priorSteps.isEmpty()
                ? "(empty)"
                : priorSteps.stream()
                .map(step -> "step %d | type=%s | tool=%s | status=%s | reason=%s | observation=%s".formatted(
                        step.getStepIndex(),
                        blankAs(step.getStepType(), "-"),
                        blankAs(step.getToolName(), "-"),
                        blankAs(step.getStatus(), "-"),
                        blankAs(step.getReason(), "-"),
                        blankAs(step.getObservationSummary(), "-")
                ))
                .collect(Collectors.joining("\n"));
        List<AgentPlanItem> latestPlan = extractLatestPlan(priorSteps);
        Set<String> completedTaskKeys = extractCompletedTaskKeys(priorSteps);
        String latestPlanText = latestPlan.isEmpty()
                ? "(empty)"
                : latestPlan.stream()
                .map(task -> "- %s | %s | %s".formatted(
                        blankAs(task.getKey(), "-"),
                        blankAs(task.getTitle(), "-"),
                        blankAs(task.getDescription(), "-")
                ))
                .collect(Collectors.joining("\n"));
        String completedTaskText = completedTaskKeys.isEmpty()
                ? "(none)"
                : completedTaskKeys.stream().collect(Collectors.joining(", "));
        String skillContext = skillRegistry.renderSkillContext(request.getSkillNames());
        String kbSelectionRule = request.getKbId() == null
                ? "- No knowledge base is selected for this conversation. Do not use kb_catalog, kb_lookup, or document_detail.\n"
                : "";
        String generalAssistantRule = request.getKbId() == null
                ? "- In general assistant mode, for direct coding/writing/planning requests, prefer a single final-answer task or a compact two-task plan. Do not invent prerequisite tasks unless you will actually execute them.\n"
                : "";

        return """
                You are the planner for a lightweight tool-use agent.
                Decide the single next action for this turn.

                Available actions:
                - finish
                - tool_call
                - respond_with_gap

                Available tools:
                %s

                Planning rules:
                - First create a concise execution plan with 2 to 5 tasks.
                - Every task must have a stable key like "task_1", "task_2".
                - Include the final answer as the last task in the plan.
                - If there is an existing plan, keep the existing task keys and ordering stable; only append new tasks when truly necessary.
                - It is acceptable to add tasks during execution when you discover missing subproblems, but do not silently drop earlier tasks.
                - currentAction.taskKey must point to one task in the plan.
                - One step can only choose one action.
                - Do not choose finish while there are unfinished non-final tasks in the plan.
                - If previous agent steps include a planner_guard step, that means finish was blocked; continue with the earliest unfinished non-final task instead of finishing again.
                - When choosing finish or respond_with_gap, currentAction.taskKey must be the final answer task.
                - Prefer finish only when the retrieved knowledge and previous tool observations are already enough for every required task.
                - Use kb_catalog when the user asks what documents exist in the knowledge base, whether the knowledge base is empty, or when you need an inventory before choosing a document.
                - Use document_detail after kb_catalog when you need to inspect one specific document more deeply.
                - For questions like "这个知识库能帮我解决哪些典型问题", "这个库主要覆盖什么内容", or "这个知识库能做什么", do not answer from filenames alone.
                - If kb_catalog shows generic filenames, or documents with zero chunks, inspect 1 to 3 representative documents with document_detail before finishing.
                - Use kb_lookup when the current retrieved context is too broad or still insufficient.
                - Use an MCP tool only when the user clearly needs external system data or that tool's capability.
                - If a tool just failed or gave weak results, you may either try one different tool or respond_with_gap.
                - Avoid repeating the exact same tool call unless there is a clear reason.
                %s\
                %s\
                - Today is %s. For latest/current/recent questions, use today's date context instead of historical guessing.
                - Return JSON only.
                - JSON schema:
                  {
                    "plan":[
                      {"key":"task_1","title":"...","description":"..."},
                      {"key":"task_2","title":"...","description":"..."}
                    ],
                    "currentAction":{
                      "taskKey":"task_1",
                      "action":"finish | tool_call | respond_with_gap",
                      "tool":"tool_name when action=tool_call",
                      "arguments":{"query":"..."},
                      "response":"optional short instruction",
                      "reason":"..."
                    }
                  }

                Conversation history:
                %s

                User question:
                %s

                Current retrieved context:
                %s

                Selected skills:
                %s

                Existing plan:
                %s

                Completed task keys:
                %s

                Previous agent steps:
                %s
                """.formatted(
                toolExecutorRegistry.describeTools(),
                kbSelectionRule,
                generalAssistantRule,
                today,
                historyText.isBlank() ? "(empty)" : historyText,
                request.getQuestion(),
                contextText.isBlank() ? "(empty)" : contextText,
                skillContext.isBlank() ? "(none)" : skillContext,
                latestPlanText,
                completedTaskText,
                stepText
        );
    }

    private PlannerDecision defaultFinishDecision(String reason) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(List.of());
        PlannerCurrentAction currentAction = new PlannerCurrentAction();
        currentAction.setAction("finish");
        currentAction.setReason(reason);
        decision.setCurrentAction(currentAction);
        return decision;
    }

    private List<AgentPlanItem> normalizePlan(List<AgentPlanItem> plan) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<AgentPlanItem> normalized = new ArrayList<>();
        int index = 1;
        for (AgentPlanItem item : plan) {
            if (item == null || !StringUtils.hasText(item.getTitle())) {
                continue;
            }
            AgentPlanItem next = new AgentPlanItem();
            next.setKey(StringUtils.hasText(item.getKey()) ? item.getKey().trim() : "task_" + index);
            next.setTitle(item.getTitle().trim());
            next.setDescription(StringUtils.hasText(item.getDescription()) ? item.getDescription().trim() : "");
            normalized.add(next);
            index++;
        }
        return List.copyOf(normalized);
    }

    private List<AgentPlanItem> stabilizePlan(List<AgentPlanItem> incomingPlan, List<AgentPlanItem> existingPlan) {
        if ((incomingPlan == null || incomingPlan.isEmpty()) && (existingPlan == null || existingPlan.isEmpty())) {
            return List.of();
        }
        if (incomingPlan == null || incomingPlan.isEmpty()) {
            return existingPlan == null ? List.of() : List.copyOf(existingPlan);
        }
        if (existingPlan == null || existingPlan.isEmpty()) {
            return List.copyOf(incomingPlan);
        }

        Map<String, AgentPlanItem> mergedByKey = new LinkedHashMap<>();
        for (AgentPlanItem task : existingPlan) {
            if (task != null && StringUtils.hasText(task.getKey())) {
                mergedByKey.put(task.getKey(), copyTask(task));
            }
        }
        for (AgentPlanItem task : incomingPlan) {
            if (task != null && StringUtils.hasText(task.getKey())) {
                mergedByKey.put(task.getKey(), copyTask(task));
            }
        }

        String finalTaskKey = incomingPlan.get(incomingPlan.size() - 1).getKey();
        List<String> orderedKeys = new ArrayList<>();
        for (AgentPlanItem task : existingPlan) {
            if (task != null && StringUtils.hasText(task.getKey()) && !task.getKey().equals(finalTaskKey)) {
                orderedKeys.add(task.getKey());
            }
        }
        for (AgentPlanItem task : incomingPlan) {
            if (task != null && StringUtils.hasText(task.getKey()) && !task.getKey().equals(finalTaskKey) && !orderedKeys.contains(task.getKey())) {
                orderedKeys.add(task.getKey());
            }
        }
        if (StringUtils.hasText(finalTaskKey)) {
            orderedKeys.add(finalTaskKey);
        }

        List<AgentPlanItem> stabilized = new ArrayList<>();
        for (String key : orderedKeys) {
            AgentPlanItem task = mergedByKey.get(key);
            if (task != null) {
                stabilized.add(task);
            }
        }
        return List.copyOf(stabilized);
    }

    private List<AgentPlanItem> extractLatestPlan(List<AgentStep> priorSteps) {
        if (priorSteps == null || priorSteps.isEmpty()) {
            return List.of();
        }
        for (int index = priorSteps.size() - 1; index >= 0; index--) {
            AgentStep step = priorSteps.get(index);
            if (step == null || !"plan".equalsIgnoreCase(step.getStepType()) || step.getArguments() == null) {
                continue;
            }
            Object rawTasks = step.getArguments().get("tasks");
            if (!(rawTasks instanceof List<?> rawList)) {
                continue;
            }
            List<AgentPlanItem> tasks = new ArrayList<>();
            for (Object rawItem : rawList) {
                if (rawItem instanceof AgentPlanItem existingItem && StringUtils.hasText(existingItem.getTitle())) {
                    tasks.add(copyTask(existingItem));
                    continue;
                }
                if (!(rawItem instanceof java.util.Map<?, ?> rawMap)) {
                    continue;
                }
                Object key = rawMap.get("key");
                Object title = rawMap.get("title");
                Object description = rawMap.get("description");
                if (!(title instanceof String titleText) || !StringUtils.hasText(titleText)) {
                    continue;
                }
                AgentPlanItem item = new AgentPlanItem();
                item.setKey(key instanceof String keyText && StringUtils.hasText(keyText) ? keyText : null);
                item.setTitle(titleText);
                item.setDescription(description instanceof String descriptionText ? descriptionText : "");
                tasks.add(item);
            }
            if (!tasks.isEmpty()) {
                return List.copyOf(tasks);
            }
        }
        return List.of();
    }

    private Set<String> extractCompletedTaskKeys(List<AgentStep> priorSteps) {
        if (priorSteps == null || priorSteps.isEmpty()) {
            return Set.of();
        }
        Set<String> completedTaskKeys = new LinkedHashSet<>();
        String activeTaskKey = null;
        boolean activeTaskHasSuccess = false;
        for (AgentStep step : priorSteps) {
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
        return completedTaskKeys;
    }

    private String resolveFallbackTaskKey(PlannerDecision decision, Set<String> completedTaskKeys) {
        if (decision == null || decision.getPlan() == null || decision.getPlan().isEmpty()) {
            return null;
        }
        if (decision.shouldFinish() || decision.shouldRespondWithGap()) {
            return decision.getPlan().get(decision.getPlan().size() - 1).getKey();
        }
        for (AgentPlanItem task : decision.getPlan()) {
            if (task != null && StringUtils.hasText(task.getKey()) && !completedTaskKeys.contains(task.getKey())) {
                return task.getKey();
            }
        }
        return decision.getPlan().get(0).getKey();
    }

    private AgentPlanItem copyTask(AgentPlanItem task) {
        AgentPlanItem copy = new AgentPlanItem();
        copy.setKey(task.getKey());
        copy.setTitle(task.getTitle());
        copy.setDescription(task.getDescription());
        return copy;
    }

    private void repairToolArguments(PlannerDecision decision, List<AgentStep> priorSteps) {
        if (decision == null || decision.getCurrentAction() == null || !decision.shouldCallTool()) {
            return;
        }
        if (!"document_detail".equalsIgnoreCase(decision.getTool())) {
            return;
        }

        Map<String, Object> originalArguments = decision.getArguments() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(decision.getArguments());
        if (hasUsableDocumentDetailArguments(originalArguments)) {
            return;
        }

        List<CatalogDocumentCandidate> candidates = extractCatalogDocumentCandidates(priorSteps);
        if (candidates.isEmpty()) {
            return;
        }

        Set<String> successfulDocumentNames = extractSuccessfulDocumentNames(priorSteps);
        CatalogDocumentCandidate chosen = candidates.stream()
                .filter(candidate -> !successfulDocumentNames.contains(candidate.documentName()))
                .findFirst()
                .orElse(candidates.get(0));

        originalArguments.put("documentName", chosen.documentName());
        originalArguments.remove("query");
        decision.getCurrentAction().setArguments(originalArguments);
    }

    private boolean hasUsableDocumentDetailArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return false;
        }
        Object docId = arguments.get("docId");
        if (docId instanceof Number) {
            return true;
        }
        if (docId instanceof String text && StringUtils.hasText(text) && text.trim().matches("\\d+")) {
            return true;
        }
        Object documentName = arguments.get("documentName");
        return documentName instanceof String text && StringUtils.hasText(text);
    }

    private List<CatalogDocumentCandidate> extractCatalogDocumentCandidates(List<AgentStep> priorSteps) {
        if (priorSteps == null || priorSteps.isEmpty()) {
            return List.of();
        }
        for (int index = priorSteps.size() - 1; index >= 0; index--) {
            AgentStep step = priorSteps.get(index);
            if (step == null
                    || !"tool_call".equalsIgnoreCase(step.getStepType())
                    || !"kb_catalog".equalsIgnoreCase(step.getToolName())
                    || !StringUtils.hasText(step.getObservationSummary())) {
                continue;
            }
            Matcher matcher = CATALOG_DOCUMENT_PATTERN.matcher(step.getObservationSummary());
            List<CatalogDocumentCandidate> candidates = new ArrayList<>();
            while (matcher.find()) {
                String docId = matcher.group(1);
                String documentName = matcher.group(2);
                if (StringUtils.hasText(docId) && StringUtils.hasText(documentName)) {
                    candidates.add(new CatalogDocumentCandidate(Long.parseLong(docId.trim()), documentName.trim()));
                }
            }
            if (!candidates.isEmpty()) {
                return List.copyOf(candidates);
            }
        }
        return List.of();
    }

    private Set<String> extractSuccessfulDocumentNames(List<AgentStep> priorSteps) {
        if (priorSteps == null || priorSteps.isEmpty()) {
            return Set.of();
        }
        Set<String> successful = new LinkedHashSet<>();
        for (AgentStep step : priorSteps) {
            if (step == null
                    || !"tool_call".equalsIgnoreCase(step.getStepType())
                    || !"document_detail".equalsIgnoreCase(step.getToolName())
                    || !"SUCCESS".equalsIgnoreCase(step.getStatus())
                    || step.getArguments() == null) {
                continue;
            }
            Object documentName = step.getArguments().get("documentName");
            if (documentName instanceof String text && StringUtils.hasText(text)) {
                successful.add(text.trim());
            }
        }
        return successful;
    }

    private record CatalogDocumentCandidate(Long docId, String documentName) {
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String blankAs(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
