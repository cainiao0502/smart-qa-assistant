package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.agent.dto.PlannerDecision;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.LlmRequest;
import com.nailinai.ragent.infra.chat.ToolCall;
import com.nailinai.ragent.infra.chat.ToolSpec;
import com.nailinai.ragent.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentPlannerService 单元测试（原生 function calling 版）。
 *
 * <p>覆盖：工具调用解析、控制工具（submit_plan / finish）语义映射、
 * 空响应与直接作答的降级、工具声明过滤、上下文渲染容错。
 */
@ExtendWith(MockitoExtension.class)
class AgentPlannerServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ToolExecutorRegistry toolExecutorRegistry;
    @Mock
    private SkillRegistry skillRegistry;

    private AgentPlannerService planner;

    @BeforeEach
    void setUp() {
        planner = new AgentPlannerService(chatClient, toolExecutorRegistry, skillRegistry);
        lenient().when(skillRegistry.renderSkillCatalog(any())).thenReturn("");
        lenient().when(skillRegistry.renderFullSkillCatalog()).thenReturn("");
        lenient().when(toolExecutorRegistry.listToolSpecs()).thenReturn(List.of());
    }

    @Test
    @DisplayName("submit_plan 与业务工具同时返回：解析为 tool_call 决策并携带计划")
    void toolCallWithInlinePlan_shouldProduceToolCallDecision() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(
                        ToolCall.of("call_1", "submit_plan", Map.of(
                                "tasks", List.of(Map.of("key", "task_1", "title", "检索")),
                                "currentTaskKey", "task_1"
                        )),
                        ToolCall.of("call_2", "kb_lookup", Map.of("query", "问题"))
                ),
                "tool_calls"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldCallTool()).isTrue();
        assertThat(decision.getTool()).isEqualTo("kb_lookup");
        assertThat(decision.getArguments()).containsEntry("query", "问题");
        assertThat(decision.getPlan()).hasSize(1);
    }

    @Test
    @DisplayName("finish 工具调用：解析为 finish 决策并保留 reason")
    void finishToolCall_shouldProduceFinishDecision() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(ToolCall.of("call_1", "finish", Map.of("taskKey", "task_1", "reason", "信息已足够"))),
                "tool_calls"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldFinish()).isTrue();
        assertThat(decision.getReason()).isEqualTo("信息已足够");
    }

    @Test
    @DisplayName("finish 且 gap=true：映射为 respond_with_gap")
    void finishWithGap_shouldProduceRespondWithGap() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(ToolCall.of("call_1", "finish", Map.of("taskKey", "task_1", "gap", true))),
                "tool_calls"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldRespondWithGap()).isTrue();
    }

    @Test
    @DisplayName("模型直接作答（无工具调用）：视为 finish 并沿用其文本")
    void directAnswerWithoutToolCall_shouldFinish() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse("直接回答", "test-provider"));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldFinish()).isTrue();
        assertThat(decision.getResponse()).isEqualTo("直接回答");
    }

    @Test
    @DisplayName("空响应：降级为 finish 且不抛异常")
    void emptyResponse_shouldFallbackToFinish() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(null, "test-provider"));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision).isNotNull();
        assertThat(decision.shouldFinish()).isTrue();
    }

    @Test
    @DisplayName("只提交计划未发起动作：返回 continue 进入下一轮，而不是收尾")
    void planOnlyWithoutAction_shouldContinue() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(ToolCall.of("call_1", "submit_plan", Map.of(
                        "tasks", List.of(Map.of("key", "task_1", "title", "检索")),
                        "currentTaskKey", "task_1"
                ))),
                "tool_calls"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.getAction()).isEqualTo("continue");
        assertThat(decision.shouldFinish()).isFalse();
        assertThat(decision.shouldCallTool()).isFalse();
        assertThat(decision.getPlan()).hasSize(1);
    }

    @Test
    @DisplayName("请求携带工具声明：tools 非空且包含两个控制工具")
    void requestShouldCarryControlToolSpecs() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse("ok", "test-provider"));

        planner.decide(buildRequest(), List.of(), List.of(), List.of());

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(chatClient).chat(captor.capture());
        List<String> toolNames = captor.getValue().tools().stream().map(ToolSpec::name).toList();
        assertThat(toolNames).contains("submit_plan", "finish");
    }

    @Test
    @DisplayName("通用助手模式（无知识库）：不向模型声明知识库工具")
    void generalAssistantMode_shouldNotExposeKnowledgeTools() {
        when(toolExecutorRegistry.listToolSpecs()).thenReturn(List.of(
                ToolSpec.of("kb_lookup", "知识库检索", Map.of()),
                ToolSpec.of("mcp_search", "外部检索", Map.of())
        ));
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse("ok", "test-provider"));

        ChatRequest request = buildRequest();
        request.setKbId(null);
        planner.decide(request, List.of(), List.of(), List.of());

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(chatClient).chat(captor.capture());
        List<String> toolNames = captor.getValue().tools().stream().map(ToolSpec::name).toList();
        assertThat(toolNames)
                .contains("mcp_search")
                .doesNotContain("kb_lookup");
    }

    @Test
    @DisplayName("上下文渲染：历史消息、检索切片与既有计划不导致异常")
    void contextRendering_shouldNotThrow() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(ToolCall.of("call_1", "finish", Map.of("taskKey", "task_1"))),
                "tool_calls"
        ));

        ChatMessage history = new ChatMessage();
        history.setRole(com.nailinai.ragent.enums.MessageRole.USER);
        history.setContent("历史消息");

        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentName("doc.md");
        chunk.setChunkIndex(0);
        chunk.setChunkText("切片内容");

        AgentStep priorStep = AgentStep.builder()
                .stepIndex(1)
                .stepType("tool_call")
                .toolName("kb_catalog")
                .status("SUCCESS")
                .arguments(Map.of("taskKey", "task_1"))
                .observationSummary("文档目录：#1 用户手册 (txt)")
                .build();

        PlannerDecision decision = planner.decide(buildRequest(), List.of(history), List.of(chunk), List.of(priorStep));

        assertThat(decision).isNotNull();
        assertThat(decision.shouldFinish()).isTrue();
    }

    @Test
    @DisplayName("输出被 length 截断且含工具调用：拦截为 respond_with_gap，不再执行参数不可信的工具")
    void truncatedToolCall_shouldBeBlocked() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(ToolCall.of("call_1", "kb_lookup", Map.of())),
                "length"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldCallTool()).isFalse();
        assertThat(decision.shouldRespondWithGap()).isTrue();
        assertThat(decision.getReason()).contains("truncated by max_tokens");
    }

    @Test
    @DisplayName("多个业务工具并发返回：只执行第一个，并在 reason 中如实告知其余未执行")
    void multipleToolCalls_shouldExecuteFirstAndReportSkipped() {
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(new ChatResponse(
                null,
                "test-provider",
                List.of(
                        ToolCall.of("call_1", "kb_catalog", Map.of()),
                        ToolCall.of("call_2", "document_detail", Map.of("docId", 1))
                ),
                "tool_calls"
        ));

        PlannerDecision decision = planner.decide(buildRequest(), List.of(), List.of(), List.of());

        assertThat(decision.shouldCallTool()).isTrue();
        assertThat(decision.getTool()).isEqualTo("kb_catalog");
        assertThat(decision.getReason())
                .contains("NOT executed")
                .contains("document_detail");
    }

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setKbId(1L);
        request.setQuestion("测试问题");
        return request;
    }
}
