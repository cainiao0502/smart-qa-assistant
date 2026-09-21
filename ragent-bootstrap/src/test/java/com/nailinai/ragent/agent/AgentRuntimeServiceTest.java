package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentPlanItem;
import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerCurrentAction;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.infra.chat.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRuntimeService 多步执行循环的单元测试。
 *
 * <p>覆盖：正常 finish、工具调用闭环、工具失败降级、Planner 返回空、
 * 取消信号、最大步数兜底、过早终止保护（planner_guard）、信息缺口响应。
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeServiceTest {

    @Mock
    private AgentPlannerService agentPlannerService;
    @Mock
    private ToolExecutorRegistry toolExecutorRegistry;
    @Mock
    private AgentRunStore agentRunStore;

    private AgentRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new AgentRuntimeService(agentPlannerService, toolExecutorRegistry, agentRunStore, 6, 30000, 60000);
    }

    @Test
    @DisplayName("Planner 直接 finish：运行成功并携带最终指令")
    void plannerDecidesFinish_shouldCompleteSuccessfully() {
        PlannerDecision decision = finishDecision("task_final", "这是最终回答", planOf("task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList())).thenReturn(decision);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getFinalInstruction()).isEqualTo("这是最终回答");
        assertThat(result.getSteps())
                .anyMatch(step -> "finish".equals(step.getStepType()) && "SUCCESS".equals(step.getStatus()));
        verify(agentRunStore).createRun(any());
    }

    @Test
    @DisplayName("工具调用后 finish：工具被真实执行、轨迹被记录、补充上下文被带回")
    void toolCallThenFinish_shouldExecuteToolAndTrace() {
        PlannerDecision toolDecision = toolCallDecision("task_1", "kb_lookup", Map.of("query", "问题"));
        // finish 决策只带最终任务，避免被 planner_guard 判定为过早终止
        PlannerDecision finishDecision = finishDecision("task_final", "综合回答", planOf("task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolDecision, finishDecision);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        ToolCallTraceResponse trace = ToolCallTraceResponse.builder()
                .toolName("kb_lookup")
                .displayName("KB Lookup")
                .source("builtin")
                .status("SUCCESS")
                .arguments(Map.of("query", "问题"))
                .summary("检索到 2 条切片")
                .durationMs(12L)
                .build();
        when(executor.execute(anyMap(), any())).thenReturn(
                ToolExecutionResult.builder()
                        .trace(trace)
                        .summary("检索到 2 条切片")
                        .supplementalContext("补充的检索上下文")
                        .build());
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getToolName()).isEqualTo("kb_lookup");
        assertThat(result.getSupplementalContexts()).containsExactly("补充的检索上下文");
        assertThat(result.getAnswerMode()).isEqualTo("rag+tool");
        verify(executor).execute(anyMap(), any());
    }

    @Test
    @DisplayName("工具执行失败：运行标记为 PARTIAL，失败轨迹被记录且不中断循环")
    void toolFailure_shouldMarkPartialAndRecordFailedTrace() {
        PlannerDecision toolDecision = toolCallDecision("task_1", "kb_lookup", Map.of("query", "问题"));
        PlannerDecision finishDecision = finishDecision("task_final", "尽力回答", planOf("task_1", "task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolDecision, finishDecision);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        when(executor.getDisplayName()).thenReturn("KB Lookup");
        when(executor.getSource()).thenReturn("builtin");
        when(executor.execute(anyMap(), any())).thenThrow(new RuntimeException("tool exploded"));
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(result.getSteps())
                .anyMatch(step -> "tool_call".equals(step.getStepType()) && "FAILED".equals(step.getStatus()));
    }

    @Test
    @DisplayName("模型请求不存在的工具：不打断循环，记录 NOT_FOUND 失败步骤并回灌可用工具")
    void unknownTool_shouldRecordNotFoundWithoutBreakingLoop() {
        PlannerDecision toolDecision = toolCallDecision("task_1", "kb_explode", Map.of("query", "问题"));
        PlannerDecision finishDecision = finishDecision("task_final", "尽力回答", planOf("task_1", "task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolDecision, finishDecision);

        when(toolExecutorRegistry.getRequired("kb_explode"))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "tool not found: kb_explode"));
        when(toolExecutorRegistry.listToolSpecs())
                .thenReturn(List.of(ToolSpec.of("kb_lookup", "检索", Map.of())));

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        // 关键点：整轮 run 不能因为一个不存在的工具名而失败
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getFailureType()).isEqualTo("NOT_FOUND");
        assertThat(result.getToolCalls().get(0).getSummary())
                .contains("does not exist")
                .contains("kb_lookup");
        // 终态语义（2026-09 收紧）：未知工具失败不算硬失败——它只是模型走错了路，
        // 模型随后改用可用工具并正常 finish 时，run 状态应为 SUCCESS 而非 PARTIAL。
        // 真实的工具执行失败/超时才保留 PARTIAL。
        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Planner 返回 null：循环安全结束，状态保持 SUCCESS")
    void plannerReturnsNull_shouldEndLoopSafely() {
        when(agentPlannerService.decide(any(), any(), any(), anyList())).thenReturn(null);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getSteps()).isEmpty();
    }

    @Test
    @DisplayName("收到取消信号：运行标记为 CANCELLED")
    void cancelSignal_shouldMarkCancelled() {
        AtomicBoolean cancel = new AtomicBoolean(true);

        AgentRuntimeResult result = service.run(
                buildRequest(), List.of(), buildRetrievalResult(), AgentRuntimeListener.NOOP, cancel);

        assertThat(result.getRun().getStatus()).isEqualTo("CANCELLED");
        assertThat(result.getFinalInstruction()).contains("取消");
    }

    @Test
    @DisplayName("达到最大步数仍未 finish：标记 PARTIAL 并提示不确定性")
    void maxStepsReached_shouldMarkPartial() {
        PlannerDecision toolDecision = toolCallDecision("task_1", "kb_lookup", Map.of("query", "问题"));
        when(agentPlannerService.decide(any(), any(), any(), anyList())).thenReturn(toolDecision);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        when(executor.execute(anyMap(), any())).thenReturn(
                ToolExecutionResult.builder().summary("ok").build());
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getFinalInstruction()).contains("max step limit");
        assertThat(result.getSteps()).hasSize(6);
    }

    @Test
    @DisplayName("Planner 连续两次试图提前 finish：被 planner_guard 拦截后强制转为信息缺口响应")
    void repeatedPrematureFinish_shouldBeBlockedByGuard() {
        PlannerDecision prematureFinish = finishDecision("task_final", "提前结束", planOf("task_1", "task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList())).thenReturn(prematureFinish);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        // 非最终任务未完成时不允许 finish，兜底转为 respond_with_gap
        assertThat(result.getAnswerMode()).isEqualTo("respond_with_gap");
        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("计划中任务执行完成后 finish：completedTaskKeys 正确累积")
    void completedTasks_shouldBeTracked() {
        List<AgentPlanItem> plan = planOf("task_1", "task_final");
        PlannerDecision toolDecision = toolCallDecision("task_1", "kb_lookup", Map.of("query", "问题"));
        toolDecision.setPlan(plan);
        // 任务 task_1 已在上一轮成功执行，finish 决策仅保留最终任务，避免被判定为过早终止
        PlannerDecision finalFinish = finishDecision("task_final", "最终回答", planOf("task_final"));
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolDecision, finalFinish);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        when(executor.execute(anyMap(), any())).thenReturn(
                ToolExecutionResult.builder().summary("ok").build());
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getRun().getStatus()).isEqualTo("SUCCESS");
        // 工具任务 task_1 执行成功并在 finish 时被标记完成
        assertThat(result.getCompletedTaskKeys()).contains("task_1");
        assertThat(result.getFinalInstruction()).isEqualTo("最终回答");
    }

    @Test
    @DisplayName("信息缺口响应：answerMode 为 respond_with_gap 并携带缺口说明")
    void respondWithGap_shouldSetAnswerMode() {
        PlannerDecision gap = gapDecision("task_final", "当前信息不足以回答");
        when(agentPlannerService.decide(any(), any(), any(), anyList())).thenReturn(gap);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getAnswerMode()).isEqualTo("respond_with_gap");
        assertThat(result.getFinalInstruction()).isEqualTo("当前信息不足以回答");
    }

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setKbId(1L);
        request.setQuestion("测试问题");
        return request;
    }

    private RetrievalResult buildRetrievalResult() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setKbId(1L);
        chunk.setChunkText("测试切片内容");
        return RetrievalResult.builder().chunks(List.of(chunk)).build();
    }

    private List<AgentPlanItem> planOf(String... keys) {
        return java.util.Arrays.stream(keys)
                .map(key -> {
                    AgentPlanItem item = new AgentPlanItem();
                    item.setKey(key);
                    item.setTitle(key);
                    item.setDescription(key);
                    return item;
                })
                .toList();
    }

    private PlannerDecision finishDecision(String taskKey, String response, List<AgentPlanItem> plan) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(plan);
        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setTaskKey(taskKey);
        action.setAction("finish");
        action.setResponse(response);
        action.setReason("task finished");
        decision.setCurrentAction(action);
        return decision;
    }

    private PlannerDecision toolCallDecision(String taskKey, String tool, Map<String, Object> arguments) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(List.of());
        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setTaskKey(taskKey);
        action.setAction("tool_call");
        action.setTool(tool);
        action.setArguments(arguments);
        action.setReason("need more info");
        decision.setCurrentAction(action);
        return decision;
    }

    private PlannerDecision gapDecision(String taskKey, String response) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(List.of());
        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setTaskKey(taskKey);
        action.setAction("respond_with_gap");
        action.setResponse(response);
        action.setReason("information gap");
        decision.setCurrentAction(action);
        return decision;
    }
}
