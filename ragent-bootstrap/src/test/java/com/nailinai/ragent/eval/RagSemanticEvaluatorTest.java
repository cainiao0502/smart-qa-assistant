package com.nailinai.ragent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.infra.chat.ChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RagSemanticEvaluator（LLM-as-judge）的单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RagSemanticEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("LLM 返回合法打分 JSON：正确解析两个维度")
    void validJudgeResponse_shouldParse() {
        when(chatClient.chat(anyString()))
                .thenReturn("基于上下文，该文档说明了流程。")
                .thenReturn("{\"faithfulness\": 0.9, \"answerRelevancy\": 0.8}");
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of("上下文内容"));

        assertThat(metrics.faithfulness()).isEqualTo(0.9);
        assertThat(metrics.answerRelevancy()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("打分 JSON 混入解释文字：仍能提取 JSON 对象")
    void judgeResponseWithNoise_shouldExtractJson() {
        when(chatClient.chat(anyString()))
                .thenReturn("回答内容")
                .thenReturn("好的，以下是评分：\n```json\n{\"faithfulness\": 0.7, \"answerRelevancy\": 0.6}\n```");
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of("上下文"));

        assertThat(metrics.faithfulness()).isEqualTo(0.7);
        assertThat(metrics.answerRelevancy()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("打分 JSON 非法：降级为空指标，不抛异常")
    void invalidJudgeResponse_shouldFallbackToEmpty() {
        when(chatClient.chat(anyString())).thenReturn("回答", "这不是 JSON");
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of("上下文"));

        assertThat(metrics.faithfulness()).isZero();
        assertThat(metrics.answerRelevancy()).isZero();
    }

    @Test
    @DisplayName("生成回答为空：直接返回空指标")
    void emptyAnswer_shouldReturnEmpty() {
        when(chatClient.chat(anyString())).thenReturn("");
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of("上下文"));

        assertThat(metrics.faithfulness()).isZero();
    }

    @Test
    @DisplayName("无检索上下文：跳过评估返回空指标")
    void emptyContext_shouldReturnEmpty() {
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of());

        assertThat(metrics.faithfulness()).isZero();
    }

    @Test
    @DisplayName("分数越界被钳制到 0-1 区间")
    void outOfRangeScore_shouldBeClamped() {
        when(chatClient.chat(anyString()))
                .thenReturn("回答")
                .thenReturn("{\"faithfulness\": 1.5, \"answerRelevancy\": -0.2}");
        RagSemanticEvaluator evaluator = new RagSemanticEvaluator(chatClient, objectMapper);

        SemanticMetrics metrics = evaluator.evaluate("问题", List.of("上下文"));

        assertThat(metrics.faithfulness()).isEqualTo(1.0);
        assertThat(metrics.answerRelevancy()).isEqualTo(0.0);
    }
}
