package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerCurrentAction;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.framework.util.TextTruncator;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.LlmRequest;
import com.nailinai.ragent.infra.chat.ToolCall;
import com.nailinai.ragent.infra.chat.ToolSpec;
import com.nailinai.ragent.skill.SkillRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 决策器（Planner）。
 *
 * <p><b>协议</b>：走 provider 原生 function calling，不再让模型输出 JSON 文本再由代码解析。
 * 控制流本身也纳入工具协议：
 * <ul>
 *   <li>{@link #TOOL_SUBMIT_PLAN}——提交/更新执行计划（对应过去的 {@code plan} 字段）</li>
 *   <li>{@link #TOOL_FINISH}——进入最终回答阶段（对应过去的 {@code finish} / {@code respond_with_gap}）</li>
 * </ul>
 * 业务动作则由 {@code ToolExecutorRegistry} 声明的工具承担（kb_* / MCP / Skill）。
 *
 * <p>返回类型仍为 {@link PlannerDecision}，因此调用方 {@code AgentRuntimeService} 的循环、
 * 计划稳定性校验与 planner_guard 逻辑无需改动。
 */
@Service
public class AgentPlannerService {

    /** 控制类工具：提交执行计划 */
    static final String TOOL_SUBMIT_PLAN = "submit_plan";
    /** 控制类工具：结束本轮并进入最终回答 */
    static final String TOOL_FINISH = "finish";

    /** planner 上下文里单条历史步骤 observation 的字符上限（工具层给全量，上下文层负责裁剪） */
    private static final int STEP_OBSERVATION_CONTEXT_CHARS = 800;

    private static final ToolSpec SUBMIT_PLAN_SPEC = ToolSpec.of(
            TOOL_SUBMIT_PLAN,
            "提交或更新本轮的执行计划。首次决策必须先调用本工具发布计划；计划确实需要变化时再次调用。",
            PlannerControlSchemas.submitPlanSchema()
    );

    private static final ToolSpec FINISH_SPEC = ToolSpec.of(
            TOOL_FINISH,
            "当已获得的信息足以回答用户问题时调用，表示结束工具调用阶段并进入最终回答。若信息不足、需要向用户说明缺口，则将 gap 设为 true。"
                    + "计划不必逐项执行：若某个计划任务的信息已被前面步骤的观察覆盖、或判断无需单独执行，"
                    + "请在 skippedTaskKeys 中声明该任务 key 与原因；已实际执行完成的任务可列入 completedTaskKeys。"
                    + "未声明且未执行的任务会被记录为「未解决」，请如实申报，不要虚报完成。",
            PlannerControlSchemas.finishSchema()
    );

    private final ChatClient chatClient;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final SkillRegistry skillRegistry;

    public AgentPlannerService(ChatClient chatClient,
                               ToolExecutorRegistry toolExecutorRegistry,
                               SkillRegistry skillRegistry) {
        this.chatClient = chatClient;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.skillRegistry = skillRegistry;
    }

    public PlannerDecision decide(ChatRequest request,
                                  List<ChatMessage> history,
                                  List<DocumentChunk> retrievedChunks,
                                  List<AgentStep> priorSteps) {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildContextBlock(request, history, retrievedChunks, priorSteps))
        );

        LlmRequest llmRequest = LlmRequest.withTools(messages, buildToolSpecs(request));
        ChatResponse response = chatClient.chat(llmRequest);
        PlannerDecision decision = toDecision(response, priorSteps);
        if (decision != null) {
            // 用量随决策一起回传：主循环据此累计「本轮循环花了多少 token」
            decision.setUsage(response.usage());
        }
        return decision;
    }

    // ------------------------------------------------------------------
    // 工具声明
    // ------------------------------------------------------------------

    private List<ToolSpec> buildToolSpecs(ChatRequest request) {
        List<ToolSpec> specs = new ArrayList<>();
        specs.add(SUBMIT_PLAN_SPEC);
        specs.add(FINISH_SPEC);

        List<ToolSpec> businessTools = toolExecutorRegistry.listToolSpecs();
        if (request.getKbId() == null) {
            // 通用助手模式：直接把知识库工具从声明中摘掉，
            // 比过去「在 prompt 里叮嘱模型不要用」要靠得住。
            businessTools = businessTools.stream()
                    .filter(spec -> !isKnowledgeTool(spec.name()))
                    .toList();
        }
        specs.addAll(businessTools);
        return List.copyOf(specs);
    }

    private boolean isKnowledgeTool(String toolName) {
        return "kb_lookup".equals(toolName)
                || "kb_catalog".equals(toolName)
                || "document_detail".equals(toolName);
    }

    // ------------------------------------------------------------------
    // 响应 → 决策
    // ------------------------------------------------------------------

    private PlannerDecision toDecision(ChatResponse response, List<AgentStep> priorSteps) {
        List<AgentPlanItem> existingPlan = extractLatestPlan(priorSteps);

        // 输出被 max_tokens 截断时，工具调用的 arguments 是残缺 JSON，绝不可执行。
        // 参照 pi 的 failToolCallsFromTruncatedMessage：直接拦截，要求模型重新发起。
        if (isTruncatedWithToolCalls(response)) {
            return terminalDecision(
                    existingPlan,
                    "上一次工具调用因输出长度限制被截断，参数不完整。请缩小参数规模后重新发起该调用。",
                    "Tool call was truncated by max_tokens; arguments may be incomplete.",
                    true
            );
        }

        Set<String> completedTaskKeys = extractCompletedTaskKeys(priorSteps);

        List<AgentPlanItem> plan = existingPlan;
        String planReason = null;
        boolean planSubmitted = false;
        List<ToolCall> businessCalls = new ArrayList<>();

        for (ToolCall call : response.toolCalls()) {
            if (TOOL_SUBMIT_PLAN.equals(call.name())) {
                planSubmitted = true;
                List<AgentPlanItem> submitted = parsePlanTasks(call.arguments());
                if (!submitted.isEmpty()) {
                    plan = stabilizePlan(submitted, existingPlan);
                }
                String reason = asText(call.arguments().get("reason"));
                if (StringUtils.hasText(reason)) {
                    planReason = reason;
                }
                continue;
            }
            businessCalls.add(call);
        }

        // 本轮动作：finish 优先于业务工具
        ToolCall actionCall = businessCalls.stream()
                .filter(call -> TOOL_FINISH.equals(call.name()))
                .findFirst()
                .orElse(businessCalls.isEmpty() ? null : businessCalls.get(0));

        // 同响应的其余业务调用：模型常在一轮里发起多个相互独立的调用（原生并行工具调用协议），
        // 运行时会按序执行它们并全部回灌观察。主调用是 finish 时其余调用直接丢弃——
        // finish 表示模型认为信息已足够，不应再执行新工具。
        List<ToolCall> additionalCalls = List.of();
        if (actionCall != null && !TOOL_FINISH.equals(actionCall.name()) && businessCalls.size() > 1) {
            additionalCalls = businessCalls.stream()
                    .filter(call -> call != actionCall)
                    .toList();
        }

        if (actionCall == null) {
            // 只提交了计划、且没有文本输出 → 本轮「无动作」，应进入下一轮而不是收尾。
            //
            // 这是模型常见的行为：把「提交计划」与「选择工具」分成两次调用。
            // 若在此判为 finish，会立刻被 planner_guard 拦截，重试后整轮放弃，
            // 用户最终拿到「信息不足」的空回答——即使工具完全可用。
            if (planSubmitted && !response.hasContent()) {
                return continueDecision(plan);
            }
            // 模型直接给出文本作答 → 收尾
            return terminalDecision(
                    plan,
                    response.hasContent() ? response.content() : null,
                    planReason != null ? planReason : "Planner produced no further action.",
                    false
            );
        }

        if (TOOL_FINISH.equals(actionCall.name())) {
            Map<String, Object> arguments = actionCall.arguments();
            boolean gap = Boolean.TRUE.equals(arguments.get("gap"));
            String instruction = asText(arguments.get("response"));
            String reason = asText(arguments.get("reason"));

            PlannerDecision decision = terminalDecision(
                    plan,
                    instruction,
                    StringUtils.hasText(reason) ? reason : "Planner decided to finish.",
                    gap
            );
            String taskKey = asText(arguments.get("taskKey"));
            if (StringUtils.hasText(taskKey)) {
                decision.getCurrentAction().setTaskKey(taskKey);
            }
            // 计划完成度的显式声明：跳过本身是正当的（信息可能已被前面步骤覆盖），
            // 但必须留痕，否则轨迹上无法区分「有意跳过」与「漏做」。
            decision.setCompletedTaskKeys(parseStringList(arguments.get("completedTaskKeys")));
            decision.setSkippedTaskKeys(parseSkipList(arguments.get("skippedTaskKeys")));
            return decision;
        }

        return toolCallDecision(actionCall, additionalCalls, plan, completedTaskKeys);
    }

    /**
     * 构造「本轮无动作、继续下一步」的决策。
     *
     * <p>用于模型单独提交计划、尚未选择工具的情况：此时既没有工具可执行，
     * 也不该结束循环，只能让运行时进入下一轮。
     */
    private PlannerDecision continueDecision(List<AgentPlanItem> plan) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(plan == null ? List.of() : List.copyOf(plan));

        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setAction("continue");
        action.setReason("A plan was submitted without an action tool; continuing to the next step.");
        decision.setCurrentAction(action);
        return decision;
    }

    private PlannerDecision terminalDecision(List<AgentPlanItem> plan,
                                             String instruction,
                                             String reason,
                                             boolean gap) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(plan == null ? List.of() : List.copyOf(plan));

        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setAction(gap ? "respond_with_gap" : "finish");
        action.setTaskKey(resolveTerminalTaskKey(plan));
        action.setResponse(instruction);
        action.setReason(reason);
        decision.setCurrentAction(action);
        return decision;
    }

    private PlannerDecision toolCallDecision(ToolCall call,
                                             List<ToolCall> additionalCalls,
                                             List<AgentPlanItem> plan,
                                             Set<String> completedTaskKeys) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(plan == null ? List.of() : List.copyOf(plan));

        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setAction("tool_call");
        action.setTool(call.name());
        action.setArguments(new LinkedHashMap<>(call.arguments()));
        action.setTaskKey(resolveCurrentTaskKey(plan, completedTaskKeys));
        action.setReason(buildToolCallReason(call, additionalCalls));
        decision.setAdditionalToolCalls(additionalCalls == null ? List.of() : List.copyOf(additionalCalls));
        decision.setCurrentAction(action);
        return decision;
    }

    /**
     * 拼接本轮工具调用的说明文字。
     *
     * <p>同一响应中的多个业务工具调用会按序全部执行（见 {@code additionalToolCalls}），
     * 说明文字告知模型这一事实，让它敢于在下一轮继续发起并行调用，而不是误以为
     * 只有第一个调用生效。</p>
     */
    private String buildToolCallReason(ToolCall call, List<ToolCall> additionalCalls) {
        StringBuilder reason = new StringBuilder("Planner selected tool " + call.name() + ".");
        if (additionalCalls != null && !additionalCalls.isEmpty()) {
            reason.append(" ")
                    .append(additionalCalls.size())
                    .append(" additional tool call(s) from the same response will be executed right after this one: ")
                    .append(additionalCalls.stream().map(ToolCall::name).collect(Collectors.joining(", ")))
                    .append(".");
        }
        return reason.toString();
    }

    /**
     * 判断本轮响应是否为「被 max_tokens 截断且含工具调用」。
     *
     * <p>截断时 {@code arguments} 是残缺 JSON，解析会降级为空参数——若照常执行，
     * 工具会收到错误参数并抛出难以定位的异常，因此必须拦在决策阶段。
     */
    private boolean isTruncatedWithToolCalls(ChatResponse response) {
        return "length".equalsIgnoreCase(response.finishReason()) && response.hasToolCalls();
    }

    private List<AgentPlanItem> parsePlanTasks(Map<String, Object> arguments) {
        Object rawTasks = arguments == null ? null : arguments.get("tasks");
        if (!(rawTasks instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<AgentPlanItem> tasks = new ArrayList<>();
        for (Object rawItem : list) {
            if (!(rawItem instanceof Map<?, ?> map)) {
                continue;
            }
            Object titleValue = map.get("title");
            if (!(titleValue instanceof String title) || !StringUtils.hasText(title)) {
                continue;
            }
            Object keyValue = map.get("key");
            Object descriptionValue = map.get("description");

            AgentPlanItem item = new AgentPlanItem();
            item.setKey(keyValue instanceof String key && StringUtils.hasText(key)
                    ? key.trim()
                    : "task_" + (tasks.size() + 1));
            item.setTitle(title.trim());
            item.setDescription(descriptionValue instanceof String description ? description.trim() : "");
            tasks.add(item);
        }
        return List.copyOf(tasks);
    }

    // ------------------------------------------------------------------
    // prompt 组装
    // ------------------------------------------------------------------

    private String buildSystemPrompt() {
        return """
                You are the planner of a lightweight tool-use agent.
                Decide the single next action for this turn.

                Workflow:
                - On the first decision, call %s to publish a plan with 2 to 5 tasks, AND call your first action tool in the SAME turn (parallel tool calls are supported and expected).
                - Never call %s on its own: a turn that only submits a plan makes no progress.
                - Then call action tools (a knowledge tool, an MCP tool, or %s). You may issue SEVERAL INDEPENDENT action tools in the SAME turn — they will all be executed in order and their observations returned together. When a call depends on the result of a previous one, issue it alone and wait for its observation.
                - Call %s again only when the plan genuinely needs to change.

                Planning rules:
                - Every task must have a stable key like "task_1", "task_2".
                - Include the final answer as the last task in the plan.
                - Keep existing task keys and ordering stable; only append new tasks when truly necessary, and never silently drop earlier tasks.
                - currentTaskKey must point to one task in the plan.
                - Do not call %s while there are unfinished non-final tasks in the plan.
                - When calling %s, taskKey must be the final answer task.
                - Prefer %s only when the retrieved knowledge and previous tool observations are enough for every required task.
                - Use kb_catalog when the user asks what documents exist in the knowledge base, whether the knowledge base is empty, or when you need an inventory before choosing a document.
                - Use document_detail after kb_catalog when you need to inspect one specific document more deeply.
                - For questions like "这个知识库能帮我解决哪些典型问题", "这个库主要覆盖什么内容", or "这个知识库能做什么", do not answer from filenames alone. Inspect 1 to 3 representative documents with document_detail before finishing.
                - Use kb_lookup when the current retrieved context is too broad or still insufficient.
                - Use an MCP tool only when the user clearly needs external system data or that tool's capability.
                - If a tool just failed or gave weak results, either try one different tool or call %s with gap=true.
                - Avoid repeating the exact same tool call unless there is a clear reason.
                - For latest/current/recent questions, rely on the date given in the context instead of guessing an older year.
                """.formatted(
                TOOL_SUBMIT_PLAN,
                TOOL_SUBMIT_PLAN,
                TOOL_FINISH,
                TOOL_SUBMIT_PLAN,
                TOOL_FINISH,
                TOOL_FINISH,
                TOOL_FINISH,
                TOOL_FINISH
        );
    }

    private String buildContextBlock(ChatRequest request,
                                     List<ChatMessage> history,
                                     List<DocumentChunk> chunks,
                                     List<AgentStep> priorSteps) {
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
                        blankAs(truncateForContext(step.getObservationSummary()), "-")
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
                : String.join(", ", completedTaskKeys);

        // 渐进式披露第一层：只注入技能目录（名称+描述），正文由模型按需调 load_skill 加载。
        // 用户勾选了技能 → 精选目录；未勾选 → 注入全量目录（否则模型无从发现可执行技能，
        // 评测 E08 三轮复现：仅靠工具描述引导，模型不会主动调用技能工具）
        String skillContext = skillRegistry.renderSkillCatalog(request.getSkillNames());
        if (!StringUtils.hasText(skillContext)) {
            skillContext = skillRegistry.renderFullSkillCatalog();
        }
        String modeHint = request.getKbId() == null
                ? "- General assistant mode: no knowledge base is selected, so knowledge-base tools are not available. For direct coding/writing/planning requests, prefer calling " + TOOL_FINISH + " right away instead of inventing prerequisite tasks.\n"
                : "";

        return """
                %s\
                Today is %s.

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
                modeHint,
                LocalDate.now(),
                historyText.isBlank() ? "(empty)" : historyText,
                request.getQuestion(),
                contextText.isBlank() ? "(empty)" : contextText,
                skillContext.isBlank() ? "(none)" : skillContext,
                latestPlanText,
                completedTaskText,
                stepText
        );
    }

    // ------------------------------------------------------------------
    // 计划提取与稳定性
    // ------------------------------------------------------------------

    /**
     * 合并新提交的计划与既有计划：保留既有顺序与 taskKey，仅追加新任务，
     * 并确保最终回答任务始终位于末位。
     */
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
            if (task != null && StringUtils.hasText(task.getKey())
                    && !task.getKey().equals(finalTaskKey) && !orderedKeys.contains(task.getKey())) {
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
                if (!(rawItem instanceof Map<?, ?> rawMap)) {
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
            if (!"FAILED".equalsIgnoreCase(step.getStatus())
                    && !"SKIPPED".equalsIgnoreCase(step.getStatus())
                    && !"plan".equalsIgnoreCase(step.getStepType())) {
                activeTaskHasSuccess = true;
            }
            if ("finish".equalsIgnoreCase(step.getStepType()) || "respond_with_gap".equalsIgnoreCase(step.getStepType())) {
                completedTaskKeys.add(taskKey);
            }
        }
        return completedTaskKeys;
    }

    private String resolveCurrentTaskKey(List<AgentPlanItem> plan, Set<String> completedTaskKeys) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        for (int index = 0; index < plan.size() - 1; index++) {
            AgentPlanItem task = plan.get(index);
            if (task != null && StringUtils.hasText(task.getKey()) && !completedTaskKeys.contains(task.getKey())) {
                return task.getKey();
            }
        }
        return resolveTerminalTaskKey(plan);
    }

    private String resolveTerminalTaskKey(List<AgentPlanItem> plan) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        return plan.get(plan.size() - 1).getKey();
    }

    private AgentPlanItem copyTask(AgentPlanItem task) {
        AgentPlanItem copy = new AgentPlanItem();
        copy.setKey(task.getKey());
        copy.setTitle(task.getTitle());
        copy.setDescription(task.getDescription());
        return copy;
    }

    private String asText(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    /** 解析字符串数组参数（如 finish 的 completedTaskKeys），非法元素直接丢弃。 */
    private List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .map(this::asText)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 解析 finish 的 skippedTaskKeys：元素为 {key, reason}。
     *
     * <p>reason 允许缺失（记为「未说明原因」），但 key 缺失的条目直接丢弃——
     * 没有任务标识的声明无法用于完成度核算。
     */
    private List<Map<String, Object>> parseSkipList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String key = asText(map.get("key"));
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String reason = asText(map.get("reason"));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("reason", reason == null ? "未说明原因" : reason);
            result.add(normalized);
        }
        return result;
    }

    /**
     * 渲染历史步骤时压缩 observation。
     *
     * <p>工具层现在会给出完整内容（见 {@code ObservationBuilder}），若在 planner 上下文中
     * 原样累积，多步之后 prompt 会迅速膨胀。裁剪发生在上下文层，而不是让工具层预先丢信息。
     */
    private String truncateForContext(String observation) {
        return TextTruncator.truncateHead(observation, STEP_OBSERVATION_CONTEXT_CHARS, 15).content();
    }

    private String blankAs(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
