package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.agent.dto.RunUsage;
import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.dto.ToolFailureType;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.infra.chat.ToolSpec;
import com.nailinai.ragent.util.ReferenceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final AgentPlannerService agentPlannerService;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final AgentRunStore agentRunStore;
    private final int maxSteps;
    private final long plannerTimeoutMs;
    private final long toolTimeoutMs;
    /** 轮间钩子（可多个，按 Spring 注入顺序依次生效）。未注册时行为与旧版完全一致。 */
    private List<AgentTurnHook> turnHooks = List.of();
    /** 连续被守卫拦截多少次后强制收尾；见 {@link #setMaxGuardBlocksPerRun(int)} */
    private int maxGuardBlocksPerRun = 2;
    /** 授权类拦截上限，默认 2；见 {@link #setMaxApprovalBlocksPerRun(int)} */
    private int maxApprovalBlocksPerRun = 2;

    public AgentRuntimeService(AgentPlannerService agentPlannerService,
                               ToolExecutorRegistry toolExecutorRegistry,
                               AgentRunStore agentRunStore,
                               @Value("${app.agent.max-steps:6}") int maxSteps,
                               @Value("${app.agent.planner-timeout-ms:30000}") long plannerTimeoutMs,
                               @Value("${app.agent.tool-timeout-ms:60000}") long toolTimeoutMs) {
        this.agentPlannerService = agentPlannerService;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.agentRunStore = agentRunStore;
        this.maxSteps = Math.max(1, maxSteps);
        this.plannerTimeoutMs = Math.max(5000, plannerTimeoutMs);
        this.toolTimeoutMs = Math.max(5000, toolTimeoutMs);
    }

    /**
     * 钩子以<b>列表</b>注入：上下文压缩、收敛守卫等策略各自实现 {@link AgentTurnHook}，互不覆盖。
     * 使用 setter 注入以保持既有构造器签名不变（测试直接 new 不受影响）。
     */
    @Autowired(required = false)
    public void setTurnHooks(List<AgentTurnHook> turnHooks) {
        this.turnHooks = turnHooks == null ? List.of() : List.copyOf(turnHooks);
    }

    /**
     * 连续被守卫拦截多少次后强制收尾。
     *
     * <p>钩子只能拦单次调用；若模型不听劝持续请求被拦的调用，步数仍会被守卫吃光。
     * 这里是运行时的硬兜底：宁可「答一部分 + 明确说明缺口」，也不把整轮步数耗尽。
     */
    @Value("${app.agent.max-guard-blocks:2}")
    public void setMaxGuardBlocksPerRun(int maxGuardBlocksPerRun) {
        this.maxGuardBlocksPerRun = Math.max(1, maxGuardBlocksPerRun);
    }

    /**
     * 授权类拦截的上限：模型可能反复请求同一个未被批准的工具，把用户拖进无意义的反复确认。
     * 达到上限后收尾，保住「答一部分 + 说明哪些步骤缺授权」而不是空转。
     */
    @Value("${app.agent.max-approval-blocks:2}")
    public void setMaxApprovalBlocksPerRun(int maxApprovalBlocksPerRun) {
        this.maxApprovalBlocksPerRun = Math.max(1, maxApprovalBlocksPerRun);
    }

    public AgentRuntimeResult run(ChatRequest request, List<ChatMessage> history, RetrievalResult retrievalResult) {
        return run(request, history, retrievalResult, AgentRuntimeListener.NOOP);
    }

    public AgentRuntimeResult run(ChatRequest request,
                                  List<ChatMessage> history,
                                  RetrievalResult retrievalResult,
                                  AgentRuntimeListener listener) {
        return run(request, history, retrievalResult, listener, null);
    }

    public AgentRuntimeResult run(ChatRequest request,
                                  List<ChatMessage> history,
                                  RetrievalResult retrievalResult,
                                  AgentRuntimeListener listener,
                                  AtomicBoolean cancelSignal) {
        LocalDateTime now = LocalDateTime.now();
        long loopStartMs = System.currentTimeMillis();
        int llmCalls = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedTokens = 0;
        int reasoningTokens = 0;
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
        // 有计划 ≠ 必须逐个执行：信息可能一次检索就被覆盖。跳过是正当行为，但必须留痕，
        // 否则轨迹上无法区分「有意跳过」与「漏做」——见 finish 的 skippedTaskKeys 声明。
        List<Map<String, Object>> skippedTaskKeys = new ArrayList<>();
        List<String> unresolvedTaskKeys = new ArrayList<>();
        // 收敛守卫状态：每个任务已消耗的工具调用次数，以及被守卫拦下的次数
        Map<String, Integer> toolCallsPerTask = new LinkedHashMap<>();
        int guardBlockCount = 0;
        int approvalBlockCount = 0;
        // 已用尽检索预算的任务：既不再允许调工具，也不再作为本轮推进目标
        Set<String> budgetExhaustedTaskKeys = new LinkedHashSet<>();

        for (int stepIndex = 1; stepIndex <= maxSteps; stepIndex++) {
            if (isCancelled(cancelSignal, terminalStatus)) {
                terminalStatus = "CANCELLED";
                finalInstruction = summaryFrom(finalInstruction, "用户取消了操作，请基于已有结果进行简洁总结。");
                break;
            }

            // 轮间钩子：只替换 Planner 的输入视图，真实 steps（落库与轨迹展示）不受影响。
            // 多个钩子依次生效（如压缩 → 守卫），任一钩子返回 null 表示沿用当前视图。
            List<AgentStep> plannerView = steps;
            for (AgentTurnHook hook : turnHooks) {
                List<AgentStep> hooked = hook.beforeTurn(plannerView);
                if (hooked != null) {
                    plannerView = hooked;
                }
            }
            PlannerDecision decision = decideNextAction(
                    request,
                    history,
                    retrievalResult,
                    plannerView,
                    plan,
                    completedTaskKeys
            );
            if (decision == null) {
                break;
            }

            // 成本观测：每次 Planner 调用都是一次真实 LLM 请求，用量按供应商上报值累计。
            // 缓存命中单独累计——system prompt 与工具 schema 每轮重发但大多命中 prompt 缓存，
            // 只统计 inputTokens 会把成本高估数倍。
            llmCalls++;
            if (decision.getUsage() != null) {
                inputTokens += decision.getUsage().inputTokens();
                outputTokens += decision.getUsage().outputTokens();
                cachedTokens += decision.getUsage().cachedTokens();
                reasoningTokens += decision.getUsage().reasoningTokens();
            }

            String previousActionKey = currentActionKey;
            if (StringUtils.hasText(previousActionKey)
                    && StringUtils.hasText(decision.getTaskKey())
                    && !Objects.equals(previousActionKey, decision.getTaskKey())
                    && hasSuccessfulTaskActivity(steps, previousActionKey)) {
                completedTaskKeys.add(previousActionKey);
            }

            // 任务预算封顶后的强制推进：真机验证发现模型会执着于已封顶的任务
            // （换个措辞、甚至换个工具想绕过去），光在观察里劝说无效。
            // 因此由运行时把本轮任务切到「下一个未完成且未封顶」的任务上——
            // 这才是让后续子问题真正获得执行机会的关键。
            String requestedTaskKey = StringUtils.hasText(decision.getTaskKey())
                    ? decision.getTaskKey()
                    : currentActionKey;
            if (!plan.isEmpty()
                    && decision.getCurrentAction() != null
                    && StringUtils.hasText(requestedTaskKey)
                    && budgetExhaustedTaskKeys.contains(requestedTaskKey)) {
                String nextTaskKey = resolveNextOpenTaskKey(plan, completedTaskKeys, budgetExhaustedTaskKeys);
                if (nextTaskKey == null) {
                    terminalStatus = "PARTIAL";
                    finalInstruction = "所有任务的检索预算都已用尽，请基于已获得的信息给出尽可能完整的回答，"
                            + "并明确指出哪些子问题尚未解决。";
                    break;
                }
                decision.getCurrentAction().setTaskKey(nextTaskKey);
                log.info("Task budget exhausted for {}; advancing to {} (runId={})",
                        requestedTaskKey, nextTaskKey, initialRun.getRunId());
            }

            if (!decision.getPlan().isEmpty() && !samePlan(plan, decision.getPlan())) {
                plan = decision.getPlan();
                currentActionKey = decision.getTaskKey();
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));

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
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));
            }

            if (decision.getCurrentAction() != null && (decision.shouldFinish() || decision.shouldRespondWithGap())) {
                String normalizedTerminalTaskKey = resolveTerminalTaskKey(plan, decision.getTaskKey());
                decision.getCurrentAction().setTaskKey(normalizedTerminalTaskKey);
                currentActionKey = normalizedTerminalTaskKey;
            }

            if (decision.shouldFinish()) {
                finalInstruction = decision.getResponse();
                applyDeclaredTaskOutcome(decision, completedTaskKeys, skippedTaskKeys);
                markTaskCompleted(completedTaskKeys, currentActionKey);
                unresolvedTaskKeys = computeUnresolvedTaskKeys(plan, completedTaskKeys, skippedTaskKeys);
                AgentStep step = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(stepIndex)
                        .stepType("finish")
                        .arguments(buildTerminalArguments(
                                currentActionKey, completedTaskKeys, skippedTaskKeys, unresolvedTaskKeys))
                        .reason(decision.getReason())
                        .observationSummary(summaryFrom(decision.getResponse(), "Planner decided to finish with current context."))
                        .status("SUCCESS")
                        .durationMs(0L)
                        .build();
                steps.add(step);
                agentRunStore.appendStep(step);
                listener.onStepCompleted(step);
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));
                break;
            }

            if (decision.shouldRespondWithGap()) {
                finalInstruction = decision.getResponse();
                answerMode = "respond_with_gap";
                applyDeclaredTaskOutcome(decision, completedTaskKeys, skippedTaskKeys);
                markTaskCompleted(completedTaskKeys, currentActionKey);
                unresolvedTaskKeys = computeUnresolvedTaskKeys(plan, completedTaskKeys, skippedTaskKeys);
                AgentStep step = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(stepIndex)
                        .stepType("respond_with_gap")
                        .arguments(buildTerminalArguments(
                                currentActionKey, completedTaskKeys, skippedTaskKeys, unresolvedTaskKeys))
                        .reason(decision.getReason())
                        .observationSummary(summaryFrom(decision.getResponse(), "Planner identified an information gap."))
                        .status("SUCCESS")
                        .durationMs(0L)
                        .build();
                steps.add(step);
                agentRunStore.appendStep(step);
                listener.onStepCompleted(step);
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));
                break;
            }

            // 「continue」表示本轮只更新了计划、没有实际动作（例如模型单独调用了 submit_plan）。
            // 此时不应结束循环，而应进入下一轮，让模型真正选择工具。
            if (isContinueDecision(decision)) {
                continue;
            }

            if (!decision.shouldCallTool()) {
                break;
            }

            Map<String, Object> normalizedArguments = decision.getArguments() == null
                    ? Map.of()
                    : new LinkedHashMap<>(decision.getArguments());

            // 工具闸门：把「该不该允许这次调用」的策略外置（pi 的 beforeToolCall 同构）。
            // 命中拦截时不执行工具，只落一条守卫步骤，让模型在下一轮改走别的任务或直接收尾。
            ToolGateDecision gateDecision = consultToolGates(
                    initialRun.getRunId(), currentActionKey, decision.getTool(),
                    normalizedArguments, toolCallsPerTask, guardBlockCount, listener);
            if (gateDecision.blocked()) {
                // 不同策略拦下调用后运行时的处置不同：预算类意味着"这个任务用掉了它的机会"，
                // 要把它从可推进目标里摘掉；授权类只是"这一次没被批准"，任务本身没有用尽预算，
                // 把任务封顶会让用户误以为整个任务失败了。
                boolean budgetRelated = gateDecision.category() == ToolGateDecision.Category.BUDGET;
                if (budgetRelated) {
                    guardBlockCount++;
                    if (StringUtils.hasText(currentActionKey)) {
                        // 封顶即生效：本轮任务从"可推进目标"里移除，下一轮切到别的任务
                        budgetExhaustedTaskKeys.add(currentActionKey);
                    }
                } else {
                    approvalBlockCount++;
                }
                AgentStep guardStep = AgentStep.builder()
                        .runId(initialRun.getRunId())
                        .stepIndex(stepIndex)
                        .stepType("guard")
                        .toolName(decision.getTool())
                        .arguments(withTaskKey(normalizedArguments, currentActionKey))
                        .reason(budgetRelated
                                ? "Tool call blocked by gate policy: per-task budget exceeded."
                                : "Tool call blocked by gate policy: approval not granted.")
                        .observationSummary(gateDecision.reason())
                        .status("SKIPPED")
                        .durationMs(0L)
                        .build();
                steps.add(guardStep);
                agentRunStore.appendStep(guardStep);
                listener.onStepCompleted(guardStep);
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));

                if (budgetRelated && guardBlockCount >= maxGuardBlocksPerRun) {
                    // 硬兜底：模型不听劝就收尾，保住「答一部分 + 说明缺口」而不是把步数耗光
                    terminalStatus = "PARTIAL";
                    finalInstruction = "单任务工具调用已达上限，请基于已获得的信息给出尽可能完整的回答，"
                            + "并明确指出哪些子问题尚未解决。";
                    break;
                }
                if (!budgetRelated && approvalBlockCount >= maxApprovalBlocksPerRun) {
                    // 授权兜底：避免模型反复索要同一权限，把用户拖进反复确认
                    terminalStatus = "PARTIAL";
                    finalInstruction = "本次运行中有工具调用未获得授权，请基于已获得的信息给出尽可能完整的回答，"
                            + "并明确说明哪些步骤因缺少授权而没有执行。";
                    break;
                }
                continue;
            }

            ToolExecutor executor;
            try {
                executor = toolExecutorRegistry.getRequired(decision.getTool());
            } catch (Exception exception) {
                // 模型可能请求不存在的工具。参照 pi 的 prepareToolCall：此处不能打断整轮循环，
                // 而要产出一条结构化的失败步骤，让模型在下一轮改用可用工具。
                ToolFailureType failureType = classifyFailure(exception);
                String observation = failureType.toObservation(
                        decision.getTool(), rootMessage(exception), availableToolNames());
                appendFailedStep(initialRun, stepIndex, decision,
                        withTaskKey(normalizedArguments, currentActionKey),
                        decision.getTool(), decision.getTool(), "unknown",
                        failureType, observation, 0L, listener, steps, toolCalls);
                terminalStatus = "PARTIAL";
                listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                        List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));
                continue;
            }

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
                ToolFailureType failureType = classifyFailure(exception);
                status = "FAILED";
                observationSummary = failureType.toObservation(
                        executor.getToolName(), rootMessage(exception), availableToolNames());
                terminalStatus = "PARTIAL";
                toolTrace = ToolCallTraceResponse.builder()
                        .toolName(executor.getToolName())
                        .displayName(executor.getDisplayName())
                        .source(executor.getSource())
                        .status("FAILED")
                        .arguments(normalizedArguments)
                        .summary(observationSummary)
                        .failureType(failureType.name())
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
            listener.onPlanUpdated(initialRun.getRunId(), plan, currentActionKey,
                    List.copyOf(completedTaskKeys), List.copyOf(skippedTaskKeys), List.copyOf(unresolvedTaskKeys));

            // 计入本任务的调用预算：失败也算——否则失败重试会把守卫绕过去
            if (StringUtils.hasText(currentActionKey)) {
                toolCallsPerTask.merge(currentActionKey, 1, Integer::sum);
            }
        }

        long executedActionCount = steps.stream()
                .filter(step -> !"plan".equalsIgnoreCase(step.getStepType()))
                .count();
        if (executedActionCount >= maxSteps && steps.stream().noneMatch(step -> "finish".equalsIgnoreCase(step.getStepType()) || "respond_with_gap".equalsIgnoreCase(step.getStepType()))) {
            finalInstruction = summaryFrom(finalInstruction, "Agent reached the max step limit. Summarize what is known and state remaining uncertainty.");
            terminalStatus = "PARTIAL";
        }

        List<ReferenceChunkResponse> references = ReferenceUtils.mergeReferences(retrievalResult.getChunks(), extraReferences);

        AgentRun run = runBuilder
                .status(terminalStatus)
                .updatedAt(LocalDateTime.now())
                .build();

        long loopDurationMs = System.currentTimeMillis() - loopStartMs;
        RunUsage usage = new RunUsage(llmCalls, inputTokens, outputTokens, cachedTokens, reasoningTokens, loopDurationMs);
        int cacheHitRate = inputTokens > 0 ? Math.round(cachedTokens * 100f / inputTokens) : 0;
        log.info("Agent loop finished: runId={}, status={}, steps={}, llmCalls={}, "
                        + "inputTokens={} (cached={}, hitRate={}%), outputTokens={} (reasoning={}), loopDurationMs={}",
                initialRun.getRunId(), terminalStatus, steps.size(), llmCalls,
                inputTokens, cachedTokens, cacheHitRate, outputTokens, reasoningTokens, loopDurationMs);

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
                .usage(usage)
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
            if (!isPrematureTerminalDecision(decision, activePlan, completedTaskKeys, workingSteps)) {
                return decision;
            }
            workingSteps.add(buildPlannerGuardStep(workingSteps, activePlan, completedTaskKeys));
        }
        return buildForcedContinuationDecision(lastDecision, lastActivePlan, completedTaskKeys);
    }

    private boolean isPrematureTerminalDecision(PlannerDecision decision,
                                                List<AgentPlanItem> activePlan,
                                                Set<String> completedTaskKeys,
                                                List<AgentStep> steps) {
        if (decision == null || activePlan == null || activePlan.size() <= 1) {
            return false;
        }
        if (!decision.shouldFinish() && !decision.shouldRespondWithGap()) {
            return false;
        }
        // 已有成功的工具调用，说明本轮确实取得了进展，此时应信任模型的收尾判断。
        //
        // 否则会误伤：任务完成度依赖「模型是否切换到下一个任务」（见 extractCompletedTaskKeys），
        // 而模型完全可能始终停留在同一个任务上把事情做完，于是一个任务都不会被标记为完成，
        // 导致「工具全部执行成功、答案也正确」的一轮被反复拦下，最终强行降级为 respond_with_gap。
        if (hasAnySuccessfulToolCall(steps)) {
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

    /** 历史步骤中是否存在成功的工具调用 */
    private boolean hasAnySuccessfulToolCall(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return steps.stream().anyMatch(step -> step != null
                && "tool_call".equalsIgnoreCase(step.getStepType())
                && "SUCCESS".equalsIgnoreCase(step.getStatus()));
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
                .observationSummary("Finish was blocked because unfinished tasks remain: " + String.join(", ", blockingTaskKeys)
                        + ". You MUST now call an action tool to make progress on them. "
                        + "Do not call submit_plan alone, and do not finish again.")
                .status("BLOCKED")
                .durationMs(0L)
                .build();
    }

    private PlannerDecision buildForcedContinuationDecision(PlannerDecision lastDecision,
                                                            List<AgentPlanItem> activePlan,
                                                            Set<String> completedTaskKeys) {
        AgentPlanItem unfinishedTask = findEarliestUnfinishedNonFinalTask(activePlan, completedTaskKeys);
        if (unfinishedTask == null) {
            return lastDecision;
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

    /**
     * 合并模型在 finish 中显式声明的任务结果。
     *
     * <p>已完成声明是补充证据（模型可能知道某个任务在更早的步骤里已经做完了），
     * 跳过声明则承载「为什么没单独执行」——这正是轨迹可审计的关键：
     * 有了它，运行结束后才能区分「信息已被覆盖，合理跳过」与「忘了做」。
     */
    private void applyDeclaredTaskOutcome(PlannerDecision decision,
                                          Set<String> completedTaskKeys,
                                          List<Map<String, Object>> skippedTaskKeys) {
        if (decision.getCompletedTaskKeys() != null) {
            completedTaskKeys.addAll(decision.getCompletedTaskKeys());
        }
        if (decision.getSkippedTaskKeys() == null || decision.getSkippedTaskKeys().isEmpty()) {
            return;
        }
        Set<String> existingKeys = skippedTaskKeys.stream()
                .map(item -> String.valueOf(item.get("key")))
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> item : decision.getSkippedTaskKeys()) {
            String key = item.get("key") == null ? null : String.valueOf(item.get("key"));
            if (StringUtils.hasText(key) && !existingKeys.contains(key)) {
                skippedTaskKeys.add(item);
                existingKeys.add(key);
            }
        }
    }

    /**
     * 计算「既未完成也未声明跳过」的任务：这是唯一真正需要关注的欠账。
     *
     * <p>不隐式补全——把它们显式暴露出来，比默默判定为完成更安全；
     * 服务端不替模型圆场，只负责如实记录。
     */
    private List<String> computeUnresolvedTaskKeys(List<AgentPlanItem> plan,
                                                   Set<String> completedTaskKeys,
                                                   List<Map<String, Object>> skippedTaskKeys) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        Set<String> skippedKeys = skippedTaskKeys.stream()
                .map(item -> String.valueOf(item.get("key")))
                .collect(java.util.stream.Collectors.toSet());
        return plan.stream()
                .map(AgentPlanItem::getKey)
                .filter(StringUtils::hasText)
                .filter(key -> !completedTaskKeys.contains(key))
                .filter(key -> !skippedKeys.contains(key))
                .toList();
    }

    /** 结束步骤的落库参数：任务完成度全量留痕，便于事后审计与回归比对。 */
    private Map<String, Object> buildTerminalArguments(String currentActionKey,
                                                       Set<String> completedTaskKeys,
                                                       List<Map<String, Object>> skippedTaskKeys,
                                                       List<String> unresolvedTaskKeys) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("taskKey", currentActionKey == null ? "" : currentActionKey);
        arguments.put("completedTaskKeys", List.copyOf(completedTaskKeys));
        arguments.put("skippedTaskKeys", List.copyOf(skippedTaskKeys));
        arguments.put("unresolvedTaskKeys", List.copyOf(unresolvedTaskKeys));
        return arguments;
    }

    private boolean hasSuccessfulTaskActivity(List<AgentStep> steps, String taskKey) {
        if (!StringUtils.hasText(taskKey) || steps == null || steps.isEmpty()) {
            return false;
        }
        // 守卫步骤（SKIPPED）不构成「有效活动」：否则被拦下的任务会被误判为已完成
        return steps.stream().anyMatch(step -> taskKey.equals(step.getArguments() == null ? null : step.getArguments().get("taskKey"))
                && !"FAILED".equalsIgnoreCase(step.getStatus())
                && !"SKIPPED".equalsIgnoreCase(step.getStatus())
                && !"plan".equalsIgnoreCase(step.getStepType())
                && !"guard".equalsIgnoreCase(step.getStepType()));
    }

    /**
     * 选出下一个可推进的任务：既未完成、也未因预算封顶。
     *
     * <p>返回 {@code null} 表示没有可推进的任务（调用方据此收尾）。
     */
    private String resolveNextOpenTaskKey(List<AgentPlanItem> plan,
                                          Set<String> completedTaskKeys,
                                          Set<String> budgetExhaustedTaskKeys) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        for (AgentPlanItem item : plan) {
            String key = item == null ? null : item.getKey();
            if (StringUtils.hasText(key)
                    && !completedTaskKeys.contains(key)
                    && !budgetExhaustedTaskKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /** 依次征询各钩子的工具闸门决策：任一拦截即生效（与 pi 的 beforeToolCall 语义一致）。 */
    private ToolGateDecision consultToolGates(String runId,
                                              String taskKey,
                                              String toolName,
                                              Map<String, Object> arguments,
                                              Map<String, Integer> toolCallsPerTask,
                                              int guardBlockCount,
                                              AgentRuntimeListener listener) {
        if (turnHooks.isEmpty()) {
            return ToolGateDecision.allow();
        }
        // 审批通道由当前请求的监听器提供：它天然携带本次连接的上下文（emitter、连接状态），
        // 因此策略不需要感知传输方式。监听器不具备确认能力时显式传 null，
        // 让策略走「没有可用通道」的判定，而不是让它在等待后才失败。
        ApprovalRequester approvalRequester = listener.supportsApproval()
                ? request -> listener.requestApproval(request)
                : null;
        ToolGateContext context = new ToolGateContext(
                runId,
                taskKey,
                toolName,
                arguments,
                StringUtils.hasText(taskKey) ? toolCallsPerTask.getOrDefault(taskKey, 0) : 0,
                guardBlockCount,
                approvalRequester
        );
        for (AgentTurnHook hook : turnHooks) {
            ToolGateDecision decision = hook.beforeToolCall(context);
            if (decision != null && decision.blocked()) {
                return decision;
            }
        }
        return ToolGateDecision.allow();
    }

    private String summaryFrom(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    /**
     * 记录一次失败的工具步骤。
     *
     * <p>失败不抛断循环：产出结构化的失败步骤与 trace（含失败分类），
     * 让模型在下一轮据此调整策略——这正是 pi 在 6 个阶段都返回错误结果而非抛异常的做法。
     */
    private void appendFailedStep(AgentRun run,
                                  int stepIndex,
                                  PlannerDecision decision,
                                  Map<String, Object> arguments,
                                  String toolName,
                                  String displayName,
                                  String source,
                                  ToolFailureType failureType,
                                  String observation,
                                  long durationMs,
                                  AgentRuntimeListener listener,
                                  List<AgentStep> steps,
                                  List<ToolCallTraceResponse> toolCalls) {
        ToolCallTraceResponse trace = ToolCallTraceResponse.builder()
                .toolName(toolName)
                .displayName(displayName)
                .source(source)
                .status("FAILED")
                .arguments(arguments)
                .summary(observation)
                .failureType(failureType.name())
                .durationMs(durationMs)
                .build();
        toolCalls.add(trace);
        listener.onToolResult(trace);

        AgentStep step = AgentStep.builder()
                .runId(run.getRunId())
                .stepIndex(stepIndex)
                .stepType("tool_call")
                .toolName(toolName)
                .arguments(arguments)
                .reason(decision.getReason())
                .observationSummary(observation)
                .status("FAILED")
                .durationMs(durationMs)
                .build();
        steps.add(step);
        agentRunStore.appendStep(step);
        listener.onStepCompleted(step);
    }

    /**
     * 把异常归类到 {@link ToolFailureType}。
     *
     * <p>分类依据是「模型该怎么应对」，而不是异常类型本身：
     * 参数类错误让模型改参数，找不到工具让模型换工具，超时让模型缩小范围。
     */
    private ToolFailureType classifyFailure(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return switch (businessException.getErrorCode()) {
                case NOT_FOUND -> ToolFailureType.NOT_FOUND;
                case BAD_REQUEST -> ToolFailureType.INVALID_ARGUMENTS;
                default -> ToolFailureType.EXECUTION_ERROR;
            };
        }
        String message = rootMessage(exception);
        if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
            return ToolFailureType.TIMEOUT;
        }
        return ToolFailureType.EXECUTION_ERROR;
    }

    /** 取最内层异常的信息：包装异常往往只有一句 "failed to call xxx"，真正的原因在最里面 */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    /** 当前可用工具名列表，用于 NOT_FOUND 时告诉模型还有哪些可选 */
    private String availableToolNames() {
        return toolExecutorRegistry.listToolSpecs().stream()
                .map(ToolSpec::name)
                .collect(Collectors.joining(", "));
    }

    private boolean isCancelled(AtomicBoolean cancelSignal, String terminalStatus) {
        return cancelSignal != null && cancelSignal.get();
    }

    /** 判断是否为「本轮仅更新计划、无实际动作」的继续信号 */
    private boolean isContinueDecision(PlannerDecision decision) {
        return decision != null && "continue".equalsIgnoreCase(decision.getAction());
    }
}
