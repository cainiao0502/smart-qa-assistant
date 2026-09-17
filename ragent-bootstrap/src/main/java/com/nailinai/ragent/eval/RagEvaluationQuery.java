package com.nailinai.ragent.eval;

import java.util.List;

/**
 * RAG 评估集中的单条查询。
 *
 * @param question          用户问题（检索输入）
 * @param expectedDocuments 期望命中的文档名关键词（与 {@code document_name} 做包含匹配）
 * @param topK              该条查询的召回数量；为空时使用评估集全局 topK
 */
public record RagEvaluationQuery(
        String question,
        List<String> expectedDocuments,
        Integer topK
) {
    public RagEvaluationQuery {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
    }
}
