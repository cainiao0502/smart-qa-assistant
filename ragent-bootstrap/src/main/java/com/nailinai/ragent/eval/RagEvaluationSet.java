package com.nailinai.ragent.eval;

import java.util.List;

/**
 * RAG 评估集：一组带期望答案的查询，用于量化检索质量。
 *
 * @param kbId    评估目标知识库
 * @param topK    全局召回数量（单条查询未指定 topK 时使用）
 * @param queries 评估查询列表
 */
public record RagEvaluationSet(
        Long kbId,
        Integer topK,
        List<RagEvaluationQuery> queries
) {
    public RagEvaluationSet {
        queries = queries == null ? List.of() : List.copyOf(queries);
        topK = topK == null ? 4 : topK;
    }

    public int effectiveTopK(RagEvaluationQuery query) {
        return query.topK() == null ? topK : query.topK();
    }
}
