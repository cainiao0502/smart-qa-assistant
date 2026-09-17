package com.nailinai.ragent.eval;

/**
 * 检索质量指标（recall@k / precision@k）。
 *
 * <p>以文档为粒度统计：检索命中的文档是否包含期望文档。
 *
 * @param hitCount       命中的期望文档数
 * @param expectedCount  期望文档总数
 * @param retrievedCount 实际返回的文档数（去重后，不超过 k）
 * @param recallAtK      召回率 = hitCount / expectedCount（期望为空时定义为 1.0）
 * @param precisionAtK   精确率 = hitCount / max(retrievedCount, 1)（无返回时定义为 0.0）
 */
public record EvaluationMetrics(
        int hitCount,
        int expectedCount,
        int retrievedCount,
        double recallAtK,
        double precisionAtK
) {

    public static EvaluationMetrics of(int hitCount, int expectedCount, int retrievedCount) {
        double recall = expectedCount == 0 ? 1.0 : (double) hitCount / expectedCount;
        double precision = retrievedCount == 0 ? 0.0 : (double) hitCount / Math.max(retrievedCount, 1);
        return new EvaluationMetrics(hitCount, expectedCount, retrievedCount, recall, precision);
    }

    public static EvaluationMetrics empty() {
        return new EvaluationMetrics(0, 0, 0, 0.0, 0.0);
    }
}
