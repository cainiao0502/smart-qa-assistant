package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompactionServiceTest {

    private ChatClient chatClient;
    private ContextCompactionService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        // trigger-chars 压低到 300，方便用短文本构造触发场景
        service = new ContextCompactionService(chatClient, 300, 2, 512);
    }

    private AgentStep step(int index, String type, String tool, String observation) {
        return AgentStep.builder()
                .runId("run-1")
                .stepIndex(index)
                .stepType(type)
                .toolName(tool)
                .reason("reason-" + index)
                .observationSummary(observation)
                .status("SUCCESS")
                .durationMs(10L)
                .build();
    }

    private ChatResponse summaryResponse(String text) {
        return new ChatResponse(text, "test-provider", List.of(), "stop");
    }

    @Test
    @DisplayName("步骤数不超过保留数：不压缩")
    void fewSteps_shouldReturnNull() {
        List<AgentStep> steps = List.of(
                step(1, "tool_call", "kb_lookup", "x".repeat(500)),
                step(2, "tool_call", "kb_lookup", "y".repeat(500))
        );
        assertThat(service.beforeTurn(steps)).isNull();
        verify(chatClient, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("观察总量不足阈值：不压缩")
    void belowThreshold_shouldReturnNull() {
        List<AgentStep> steps = List.of(
                step(1, "tool_call", "kb_lookup", "短观察"),
                step(2, "tool_call", "kb_catalog", "短观察"),
                step(3, "tool_call", "kb_lookup", "短观察"),
                step(4, "tool_call", "kb_catalog", "短观察")
        );
        assertThat(service.beforeTurn(steps)).isNull();
        verify(chatClient, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("触发压缩：早期步骤变一条摘要，最近两步与 plan 步骤保留原文")
    void overThreshold_shouldCompact() {
        List<AgentStep> steps = new ArrayList<>();
        steps.add(step(0, "plan", null, "Planner generated an execution plan."));
        steps.add(step(1, "tool_call", "kb_catalog", "目录观察".repeat(80)));
        steps.add(step(2, "tool_call", "document_detail", "详情观察".repeat(80)));
        steps.add(step(3, "tool_call", "kb_lookup", "检索观察".repeat(80)));
        steps.add(step(4, "tool_call", "kb_lookup", "最新观察"));

        when(chatClient.chat(any(LlmRequest.class))).thenReturn(summaryResponse("## 目标\n查询嵌入模型。\n## 关键发现\ndocId=2"));

        List<AgentStep> view = service.beforeTurn(steps);

        assertThat(view).isNotNull();
        // 5 步 -> 摘要 1 条 + 保留最近 2 步 + plan 1 条 = 4 条
        assertThat(view).hasSize(4);
        assertThat(view).anySatisfy(s -> {
            assertThat(s.getStepType()).isEqualTo("compaction");
            assertThat(s.getObservationSummary()).contains("docId=2");
        });
        assertThat(view).anySatisfy(s -> assertThat(s.getStepType()).isEqualTo("plan"));
        // 最近两步原样保留
        assertThat(view).anySatisfy(s -> "最新观察".equals(s.getObservationSummary()));
        assertThat(view).anySatisfy(s -> "检索观察".repeat(80).equals(s.getObservationSummary()));
        // 不再包含被压缩的原始步骤
        assertThat(view).noneMatch(s -> "目录观察".repeat(80).equals(s.getObservationSummary()));
        // 视图按 stepIndex 升序
        assertThat(view).extracting(AgentStep::getStepIndex).isSorted();
    }

    @Test
    @DisplayName("摘要请求：无工具声明、带 maxTokens、prompt 包含观察与保留要求")
    void summarizeRequest_shouldCarryConstraints() {
        List<AgentStep> steps = List.of(
                step(1, "tool_call", "kb_catalog", "目录观察".repeat(80)),
                step(2, "tool_call", "document_detail", "详情观察".repeat(80)),
                step(3, "tool_call", "kb_lookup", "检索观察".repeat(80)),
                step(4, "tool_call", "kb_lookup", "最新观察")
        );
        when(chatClient.chat(any(LlmRequest.class))).thenReturn(summaryResponse("摘要"));

        service.beforeTurn(steps);

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(chatClient).chat(captor.capture());
        LlmRequest request = captor.getValue();
        assertThat(request.tools()).isEmpty();
        assertThat(request.maxTokens()).isEqualTo(512);
        String prompt = request.prompt();
        assertThat(prompt).contains("kb_catalog").contains("## 重要数据").contains("原样保留");
    }

    @Test
    @DisplayName("LLM 失败：降级返回 null，不抛异常")
    void summarizeFailure_shouldFallbackToNull() {
        List<AgentStep> steps = List.of(
                step(1, "tool_call", "kb_catalog", "目录观察".repeat(80)),
                step(2, "tool_call", "document_detail", "详情观察".repeat(80)),
                step(3, "tool_call", "kb_lookup", "检索观察".repeat(80)),
                step(4, "tool_call", "kb_lookup", "最新观察")
        );
        when(chatClient.chat(any(LlmRequest.class))).thenThrow(new RuntimeException("provider down"));

        assertThat(service.beforeTurn(steps)).isNull();
    }

    @Test
    @DisplayName("增量更新：视图已含旧摘要时，新摘要请求包含旧摘要文本")
    void existingCompaction_shouldUpdateIncrementally() {
        List<AgentStep> steps = new ArrayList<>();
        steps.add(step(1, "compaction", null, "【旧摘要】目标：查询嵌入模型"));
        steps.add(step(2, "tool_call", "kb_lookup", "新观察".repeat(80)));
        steps.add(step(3, "tool_call", "kb_lookup", "新观察2".repeat(80)));
        steps.add(step(4, "tool_call", "kb_lookup", "新观察3".repeat(80)));
        steps.add(step(5, "tool_call", "kb_lookup", "最新观察"));

        when(chatClient.chat(any(LlmRequest.class))).thenReturn(summaryResponse("合并后的摘要"));

        List<AgentStep> view = service.beforeTurn(steps);

        assertThat(view).isNotNull();
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(chatClient).chat(captor.capture());
        assertThat(captor.getValue().prompt()).contains("【旧摘要】目标：查询嵌入模型");
        // 旧摘要条目不出现在新视图里（已被合并后的新摘要替代）
        assertThat(view).noneMatch(s -> "【旧摘要】目标：查询嵌入模型".equals(s.getObservationSummary()));
    }
}
