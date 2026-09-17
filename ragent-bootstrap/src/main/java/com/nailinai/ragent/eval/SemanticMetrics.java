package com.nailinai.ragent.eval;

/**
 * 语义级评估指标（LLM-as-judge）。
 *
 * <p>在文档级指标（recall@k / precision@k）之上，用 LLM 对"问题 - 检索上下文 -
 * 生成回答"三元组打分，衡量生成质量：
 *
 * @param faithfulness    忠实度：回答是否严格基于检索上下文，未编造上下文之外的信息（0-1）
 * @param answerRelevancy 回答相关性：回答是否准确回应了用户问题（0-1）
 */
public record SemanticMetrics(
        double faithfulness,
        double answerRelevancy
) {

    public static SemanticMetrics empty() {
        return new SemanticMetrics(0.0, 0.0);
    }
}
