package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.agent.AgentRunStore;
import com.nailinai.ragent.agent.ChatAgentOrchestrator;
import com.nailinai.ragent.agent.FinalAnswerComposer;
import com.nailinai.ragent.chat.guardrail.GuardrailManager;
import com.nailinai.ragent.chat.guardrail.GuardrailResult;
import com.nailinai.ragent.chat.intent.IntentClassifier;
import com.nailinai.ragent.chat.intent.IntentDecision;
import com.nailinai.ragent.chat.intent.Intent;
import com.nailinai.ragent.chat.intent.IntentRouter;
import com.nailinai.ragent.chat.service.MemoryService;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.util.CitationValidator;
import com.nailinai.ragent.util.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatServiceImpl 聊天主链路的单元测试。
 *
 * <p>覆盖：护栏拦截（BAD_REQUEST）、会话并发冲突（CONFLICT）、
 * CLARIFY / DIRECT / AGENT 三种路由分支、Agent 编排失败时运行状态回写。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private MemoryService memoryService;
    @Mock
    private ChatAgentOrchestrator chatAgentOrchestrator;
    @Mock
    private FinalAnswerComposer finalAnswerComposer;
    @Mock
    private AgentRunStore agentRunStore;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private IntentClassifier intentClassifier;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private SessionExecutionGuard sessionExecutionGuard;
    @Mock
    private ContextWindowManager contextWindowManager;
    @Mock
    private GuardrailManager guardrailManager;
    @Mock
    private ScheduledExecutorService sseHeartbeatExecutor;

    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatServiceImpl(
                memoryService, chatAgentOrchestrator, finalAnswerComposer, agentRunStore,
                promptBuilder, chatClient, chatMessageMapper, knowledgeBaseMapper,
                intentClassifier, intentRouter, sessionExecutionGuard, contextWindowManager,
                guardrailManager, new CitationValidator(), sseHeartbeatExecutor, 4, 0.4, 6, false);
        lenient().when(contextWindowManager.trimHistory(any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("护栏拒绝：抛出 BAD_REQUEST，且不触碰会话锁")
    void guardrailDenied_shouldThrowBadRequestBeforeLock() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.fail("输入包含疑似提示注入内容"));

        assertThatThrownBy(() -> service.chat(buildRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(be.getMessage()).contains("注入");
                });
        verify(sessionExecutionGuard, never()).tryAcquire(anyString());
    }

    @Test
    @DisplayName("会话正在处理：抛出 CONFLICT")
    void sessionBusy_shouldThrowConflict() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.chat(buildRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("CLARIFY 分支：返回澄清响应并持久化")
    void clarify_shouldReturnClarifyAndPersist() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(true);
        when(intentClassifier.classify(any())).thenReturn(IntentDecision.clarify("请补充说明"));
        when(intentRouter.route(any(), any(), any())).thenReturn(IntentRouter.RoutingOutcome.clarify("请补充说明"));

        ChatResponse response = service.chat(buildRequest());

        assertThat(response.getAnswerMode()).isEqualTo("clarify");
        assertThat(response.getClarification()).isEqualTo("请补充说明");
        verify(memoryService).saveAssistantMessage(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DIRECT 分支：直连 LLM 返回 chat 回答")
    void direct_shouldReturnChatAnswer() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(true);
        when(intentClassifier.classify(any())).thenReturn(IntentDecision.system());
        when(intentRouter.route(any(), any(), any())).thenReturn(IntentRouter.RoutingOutcome.direct("direct-prompt"));
        when(promptBuilder.buildPrompt(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("direct-prompt");
        when(chatClient.chat("direct-prompt")).thenReturn("直连回答");

        ChatResponse response = service.chat(buildRequest());

        assertThat(response.getAnswerMode()).isEqualTo("chat");
        assertThat(response.getAnswer()).isEqualTo("直连回答");
        verify(memoryService).saveAssistantMessage(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("AGENT 分支：编排完成并回写运行状态与引用")
    void agent_shouldCompleteRunAndPersist() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(true);
        when(intentClassifier.classify(any())).thenReturn(new IntentDecision(Intent.KB, null));

        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = buildOrchestration();
        when(intentRouter.route(any(), any(), any())).thenReturn(IntentRouter.RoutingOutcome.agent(orchestration));
        when(finalAnswerComposer.composeAnswer(anyString())).thenReturn("编排回答");

        ChatResponse response = service.chat(buildRequest());

        assertThat(response.getRunId()).isEqualTo("run-1");
        assertThat(response.getRunStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAnswer()).isEqualTo("编排回答");
        assertThat(response.getAnswerMode()).isEqualTo("agent");
        // 完成运行时会一并落库成本/耗时统计，因此走 4 参重载
        verify(agentRunStore).completeRun(
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.eq("编排回答"),
                any());
        verify(memoryService).saveAssistantMessage(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("AGENT 编排失败：运行状态回写 FAILED 后抛出异常")
    void agentFailure_shouldMarkRunFailedAndRethrow() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(true);
        when(intentClassifier.classify(any())).thenReturn(new IntentDecision(Intent.KB, null));

        ChatAgentOrchestrator.ToolOrchestrationResult orchestration = buildOrchestration();
        when(intentRouter.route(any(), any(), any())).thenReturn(IntentRouter.RoutingOutcome.agent(orchestration));
        when(finalAnswerComposer.composeAnswer(anyString())).thenThrow(new RuntimeException("compose failed"));

        assertThatThrownBy(() -> service.chat(buildRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("compose failed");
        verify(agentRunStore).completeRun("run-1", "FAILED", null);
    }

    @Test
    @DisplayName("会话锁在异常路径下也会被释放")
    void sessionLock_shouldBeReleasedOnException() {
        when(guardrailManager.validate(any())).thenReturn(GuardrailResult.pass());
        when(sessionExecutionGuard.tryAcquire(anyString())).thenReturn(true);
        when(intentClassifier.classify(any())).thenThrow(new RuntimeException("classifier down"));

        assertThatThrownBy(() -> service.chat(buildRequest()))
                .isInstanceOf(RuntimeException.class);
        verify(sessionExecutionGuard).release(anyString());
    }

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setKbId(1L);
        request.setQuestion("测试问题");
        return request;
    }

    private ChatAgentOrchestrator.ToolOrchestrationResult buildOrchestration() {
        return ChatAgentOrchestrator.ToolOrchestrationResult.builder()
                .runId("run-1")
                .runStatus("SUCCESS")
                .finalPrompt("final prompt")
                .answerMode("agent")
                .retrievalResult(RetrievalResult.builder()
                        .originalQuery("测试问题")
                        .effectiveQuery("测试问题")
                        .queryRewritten(false)
                        .reranked(false)
                        .chunks(List.of())
                        .build())
                .references(List.of())
                .toolCalls(List.of())
                .agentPlan(List.of())
                .completedTaskKeys(List.of())
                .agentSteps(List.of())
                .build();
    }
}
