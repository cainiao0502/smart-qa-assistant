package com.nailinai.ragent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.router.RoleChatClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 语义级 RAG 评估器（LLM-as-judge，参考 RAGAS 指标设计）。
 *
 * <p>对每条查询：基于检索到的切片用 LLM 生成回答，再让 LLM 对
 * "问题 - 上下文 - 回答" 三元组打分，得到：
 * <ul>
 *   <li>faithfulness：回答是否忠于检索上下文（衡量幻觉程度）；</li>
 *   <li>answerRelevancy：回答是否回应了用户问题。</li>
 * </ul>
 *
 * <p>依赖真实 LLM 调用，JSON 解析失败时降级为空指标（0 分），不阻断评估。
 */
@Component
public class RagSemanticEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RagSemanticEvaluator.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    /** 语义评估（回答生成 + judge 打分）走 judge 角色的模型（未配置时回落主模型） */
    private RoleChatClients roleChatClients;

    public RagSemanticEvaluator(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRoleChatClients(RoleChatClients roleChatClients) {
        this.roleChatClients = roleChatClients;
    }

    private ChatClient judgeClient() {
        return roleChatClients != null
                ? roleChatClients.forRole(RoleChatClients.ROLE_JUDGE)
                : chatClient;
    }

    /**
     * 完整语义评估：生成回答 + 打分。
     */
    public SemanticMetrics evaluate(String question, List<String> chunkTexts) {
        if (!StringUtils.hasText(question) || chunkTexts == null || chunkTexts.isEmpty()) {
            return SemanticMetrics.empty();
        }
        String answer = generateAnswer(question, chunkTexts);
        if (!StringUtils.hasText(answer)) {
            return SemanticMetrics.empty();
        }
        return judge(question, chunkTexts, answer);
    }

    /**
     * 基于检索上下文生成回答。
     */
    String generateAnswer(String question, List<String> chunkTexts) {
        try {
            String prompt = """
                    You are a RAG assistant. Answer the question strictly based on the provided context.
                    If the context does not contain enough information, say so explicitly.
                    Keep the answer concise.

                    Context:
                    %s

                    Question:
                    %s
                    """.formatted(renderContext(chunkTexts), question);
            return judgeClient().chat(prompt);
        } catch (RuntimeException ex) {
            log.warn("semantic evaluation: answer generation failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * LLM-as-judge：对回答的忠实度与相关性打分。
     */
    SemanticMetrics judge(String question, List<String> chunkTexts, String answer) {
        try {
            String prompt = """
                    You are a strict evaluator for RAG answers. Score the answer on two dimensions.
                    - faithfulness: how well the answer sticks to the provided context without hallucinating. 1.0 = fully grounded, 0.0 = fabricated.
                    - answerRelevancy: how directly the answer addresses the user question. 1.0 = fully relevant, 0.0 = off-topic.
                    Return JSON only: {"faithfulness": 0.0-1.0, "answerRelevancy": 0.0-1.0}

                    Context:
                    %s

                    Question:
                    %s

                    Answer:
                    %s
                    """.formatted(renderContext(chunkTexts), question, answer);
            return parse(judgeClient().chat(prompt));
        } catch (RuntimeException ex) {
            log.warn("semantic evaluation: judge call failed: {}", ex.getMessage());
            return SemanticMetrics.empty();
        }
    }

    SemanticMetrics parse(String response) {
        if (!StringUtils.hasText(response)) {
            return SemanticMetrics.empty();
        }
        try {
            String json = extractJsonObject(response);
            JsonNode root = objectMapper.readTree(json);
            double faithfulness = clampScore(root.path("faithfulness").asDouble(0.0));
            double answerRelevancy = clampScore(root.path("answerRelevancy").asDouble(0.0));
            return new SemanticMetrics(faithfulness, answerRelevancy);
        } catch (Exception ex) {
            log.warn("semantic evaluation: failed to parse judge response: {}", ex.getMessage());
            return SemanticMetrics.empty();
        }
    }

    private String renderContext(List<String> chunkTexts) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < chunkTexts.size(); index++) {
            builder.append("[chunk ").append(index + 1).append("] ")
                    .append(chunkTexts.get(index).trim())
                    .append("\n");
        }
        return builder.toString();
    }

    private double clampScore(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
}
