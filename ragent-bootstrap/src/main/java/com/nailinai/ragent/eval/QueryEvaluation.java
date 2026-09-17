package com.nailinai.ragent.eval;

import com.nailinai.ragent.entity.DocumentChunk;

import java.util.List;

/**
 * 单条查询的评估结果。
 *
 * @param query              原始查询
 * @param retrievedDocuments 实际命中的文档名（按检索顺序，去重，最多 k 个）
 * @param retrievedChunks    实际检索到的切片（供语义评估使用）
 * @param metrics            该条查询的文档级指标
 */
public record QueryEvaluation(
        RagEvaluationQuery query,
        List<String> retrievedDocuments,
        List<DocumentChunk> retrievedChunks,
        EvaluationMetrics metrics
) {
    public QueryEvaluation {
        retrievedDocuments = retrievedDocuments == null ? List.of() : List.copyOf(retrievedDocuments);
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
    }

    public List<String> chunkTexts() {
        return retrievedChunks.stream()
                .map(DocumentChunk::getChunkText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }
}
