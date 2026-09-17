package com.nailinai.ragent.eval;

import java.util.List;

/**
 * 一次消融组合的评估报告。
 *
 * @param queryRewriteEnabled 是否启用查询改写
 * @param rerankEnabled       是否启用重排
 * @param queries             各条查询的明细
 * @param summary             组合汇总指标（各条查询的均值）
 */
public record EvaluationReport(
        boolean queryRewriteEnabled,
        boolean rerankEnabled,
        List<QueryEvaluation> queries,
        EvaluationMetrics summary
) {

    public static EvaluationReport aggregate(boolean rewrite, boolean rerank, List<QueryEvaluation> queries) {
        if (queries.isEmpty()) {
            return new EvaluationReport(rewrite, rerank, List.of(), EvaluationMetrics.empty());
        }
        int hit = 0;
        int expected = 0;
        int retrieved = 0;
        for (QueryEvaluation query : queries) {
            hit += query.metrics().hitCount();
            expected += query.metrics().expectedCount();
            retrieved += query.metrics().retrievedCount();
        }
        return new EvaluationReport(rewrite, rerank, List.copyOf(queries), EvaluationMetrics.of(hit, expected, retrieved));
    }

    public String compactSummary() {
        return String.format(
                "rewrite=%s rerank=%s | recall@k=%.3f precision@k=%.3f (hit %d/%d)",
                queryRewriteEnabled, rerankEnabled,
                summary().recallAtK(), summary().precisionAtK(),
                summary().hitCount(), summary().expectedCount()
        );
    }
}
