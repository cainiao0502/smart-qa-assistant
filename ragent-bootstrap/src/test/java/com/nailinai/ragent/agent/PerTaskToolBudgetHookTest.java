package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerCurrentAction;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 收敛守卫测试。
 *
 * <p>背景：真机验证时模型对同一个子问题连续 6 次换措辞重查，把 maxSteps 耗尽，
 * 导致后续子问题从未执行、用户只拿到 PARTIAL 半截答案。守卫把「同一任务的工具调用次数」
 * 设成硬预算，超限的调用被拦下并回灌原因（参考 pi 的 beforeToolCall 外置策略）。
 */
@ExtendWith(MockitoExtension.class)
class PerTaskToolBudgetHookTest {

    private static final String RUN_ID = "run_test";

    @Mock
    private ToolExecutorRegistry toolExecutorRegistry;
    @Mock
    private AgentRunStore agentRunStore;
    @Mock
    private AgentPlannerService agentPlannerService;

    @Test
    @DisplayName("调用次数未达上限：放行")
    void belowBudget_shouldAllow() {
        PerTaskToolBudgetHook hook = new PerTaskToolBudgetHook(3);

        ToolGateDecision decision = hook.beforeToolCall(
                new ToolGateContext(RUN_ID, "task_1", "kb_lookup", Map.of("query", "问题"), 2, 0));

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("调用次数达到上限：拦截，且原因里说明任务号与上限")
    void atBudget_shouldBlockWithReason() {
        PerTaskToolBudgetHook hook = new PerTaskToolBudgetHook(3);

        ToolGateDecision decision = hook.beforeToolCall(
                new ToolGateContext(RUN_ID, "task_1", "kb_lookup", Map.of("query", "问题"), 3, 0));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason())
                .contains("task_1")
                .contains("上限")
                .contains("finish");
    }

    @Test
    @DisplayName("无任务标识时不拦截：没有计划时守卫不介入")
    void withoutTaskKey_shouldAllow() {
        PerTaskToolBudgetHook hook = new PerTaskToolBudgetHook(1);

        ToolGateDecision decision = hook.beforeToolCall(
                new ToolGateContext(RUN_ID, null, "kb_lookup", Map.of(), 99, 0));

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("运行时联动：同任务超预算的调用被拦成 guard 步骤，连续拦截后强制收尾")
    void runtime_shouldBlockAndForceStop() {
        // 模型永远请求同一个任务，且每次参数都不同（模拟"换个措辞再查一遍"）
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolCallDecision("task_1", Map.of("query", "备份 保留策略")));

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        when(executor.execute(anyMap(), any())).thenReturn(
                ToolExecutionResult.builder().summary("ok").build());
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeService service =
                new AgentRuntimeService(agentPlannerService, toolExecutorRegistry, agentRunStore, 6, 30000, 60000);
        service.setTurnHooks(List.of(new PerTaskToolBudgetHook(2)));
        service.setMaxGuardBlocksPerRun(2);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        List<AgentStep> guardSteps = result.getSteps().stream()
                .filter(step -> "guard".equals(step.getStepType()))
                .toList();
        List<AgentStep> toolSteps = result.getSteps().stream()
                .filter(step -> "tool_call".equals(step.getStepType()))
                .toList();

        // 预算内只执行 2 次；其余请求被守卫拦下（不再烧步数）
        assertThat(toolSteps).hasSize(2);
        assertThat(guardSteps).hasSize(2);
        assertThat(guardSteps).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo("SKIPPED");
            assertThat(step.getObservationSummary()).contains("task_1");
        });
        // 连续拦截达到阈值后收尾：保住"答一部分 + 说明缺口"，而不是把步数耗尽
        assertThat(result.getRun().getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getFinalInstruction()).contains("单任务工具调用已达上限");
    }

    @Test
    @DisplayName("未安装守卫钩子时行为不变：同任务可继续调用（守卫是可插拔策略，不是硬编码）")
    void withoutHook_shouldBehaveAsBefore() {
        when(agentPlannerService.decide(any(), any(), any(), anyList()))
                .thenReturn(toolCallDecision("task_1", Map.of("query", "问题")));

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getToolName()).thenReturn("kb_lookup");
        when(executor.execute(anyMap(), any())).thenReturn(
                ToolExecutionResult.builder().summary("ok").build());
        when(toolExecutorRegistry.getRequired("kb_lookup")).thenReturn(executor);

        AgentRuntimeService service =
                new AgentRuntimeService(agentPlannerService, toolExecutorRegistry, agentRunStore, 6, 30000, 60000);

        AgentRuntimeResult result = service.run(buildRequest(), List.of(), buildRetrievalResult());

        assertThat(result.getSteps()).hasSize(6);
        assertThat(result.getSteps()).noneMatch(step -> "guard".equals(step.getStepType()));
    }

    private PlannerDecision toolCallDecision(String taskKey, Map<String, Object> arguments) {
        PlannerDecision decision = new PlannerDecision();
        decision.setPlan(List.of());
        PlannerCurrentAction action = new PlannerCurrentAction();
        action.setTaskKey(taskKey);
        action.setAction("tool_call");
        action.setTool("kb_lookup");
        action.setArguments(arguments);
        action.setReason("need more info");
        decision.setCurrentAction(action);
        return decision;
    }

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-guard");
        request.setKbId(1L);
        request.setQuestion("生产环境的备份是怎么安排的？还有检索慢该先看哪里？");
        return request;
    }

    private RetrievalResult buildRetrievalResult() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setKbId(1L);
        chunk.setChunkText("测试切片内容");
        return RetrievalResult.builder().chunks(List.of(chunk)).build();
    }
}
