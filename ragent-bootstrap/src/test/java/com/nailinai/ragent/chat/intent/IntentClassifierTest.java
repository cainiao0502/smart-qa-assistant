package com.nailinai.ragent.chat.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.mcp.McpToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IntentClassifier 意图路由的单元测试。
 *
 * <p>覆盖：规则直判（system/kb/mcp）、LLM 解析、CLARIFY 默认文案、
 * 非法响应与异常时的 KB 兜底。
 */
@ExtendWith(MockitoExtension.class)
class IntentClassifierTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private McpToolCatalog mcpToolCatalog;

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier(chatClient, new ObjectMapper(), mcpToolCatalog);
    }

    @Test
    @DisplayName("无知识库、无技能、未开启 Agent：直接判定 SYSTEM，不调用 LLM")
    void plainChat_shouldReturnSystemWithoutLlm() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setQuestion("帮我写一段冒泡排序");

        IntentDecision decision = classifier.classify(request);

        assertThat(decision.intent()).isEqualTo(Intent.SYSTEM);
        verify(chatClient, never()).chat(anyString());
    }

    @Test
    @DisplayName("仅选择知识库：直接判定 KB，不调用 LLM")
    void kbOnly_shouldReturnKbWithoutLlm() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setKbId(1L);
        request.setQuestion("知识库里关于部署的内容有哪些？");

        IntentDecision decision = classifier.classify(request);

        assertThat(decision.intent()).isEqualTo(Intent.KB);
        verify(chatClient, never()).chat(anyString());
    }

    @Test
    @DisplayName("仅启用技能：直接判定 MCP，不调用 LLM")
    void skillOnly_shouldReturnMcpWithoutLlm() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setSkillNames(List.of("codeagent"));
        request.setQuestion("帮我分析这段代码");

        IntentDecision decision = classifier.classify(request);

        assertThat(decision.intent()).isEqualTo(Intent.MCP);
        verify(chatClient, never()).chat(anyString());
    }

    @Test
    @DisplayName("LLM 返回 KB 意图：正确解析")
    void llmReturnsKb_shouldParse() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of());
        when(chatClient.chat(anyString())).thenReturn("{\"intent\":\"KB\"}");

        ChatRequest request = buildAgentRequest();
        IntentDecision decision = classifier.classify(request);

        assertThat(decision.intent()).isEqualTo(Intent.KB);
    }

    @Test
    @DisplayName("LLM 返回 CLARIFY 且无追问文案：使用默认澄清问题")
    void llmReturnsClarifyWithoutText_shouldUseDefault() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of());
        when(chatClient.chat(anyString())).thenReturn("{\"intent\":\"CLARIFY\"}");

        IntentDecision decision = classifier.classify(buildAgentRequest());

        assertThat(decision.intent()).isEqualTo(Intent.CLARIFY);
        assertThat(decision.clarification()).isNotBlank();
    }

    @Test
    @DisplayName("LLM 返回非法响应：降级为 KB（知识库模式下）")
    void llmReturnsGarbage_shouldFallbackToKb() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of());
        when(chatClient.chat(anyString())).thenReturn("这不是 JSON");

        IntentDecision decision = classifier.classify(buildAgentRequest());

        assertThat(decision.intent()).isEqualTo(Intent.KB);
    }

    @Test
    @DisplayName("LLM 调用抛异常：降级为 KB（知识库模式下）")
    void llmThrows_shouldFallbackToKb() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of());
        when(chatClient.chat(anyString())).thenThrow(new RuntimeException("llm down"));

        IntentDecision decision = classifier.classify(buildAgentRequest());

        assertThat(decision.intent()).isEqualTo(Intent.KB);
    }

    private ChatRequest buildAgentRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setKbId(1L);
        request.setAgentEnabled(true);
        request.setQuestion("这个问题需要综合判断");
        return request;
    }
}
