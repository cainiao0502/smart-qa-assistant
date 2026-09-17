package com.nailinai.ragent.chat.retrieve;

import java.util.List;

/**
 * 一次检索请求。
 *
 * @param ownerUserId 当前登录用户，用于在 SQL 层过滤知识库归属。
 *                    为 {@code null} 表示内部调用（如离线评估跑批），此时不做归属过滤；
 *                    用户请求一律经过登录拦截器、取值来自 {@code UserIdHolder}，不会为 null。
 */
public record SearchRequest(
        Long kbId,
        String effectiveQuery,
        String embeddingLiteral,
        int topK,
        List<Long> documentIds,
        List<String> fileTypes,
        String documentNameKeyword,
        Long ownerUserId
) {
    public static SearchRequest of(Long kbId,
                                   String effectiveQuery,
                                   String embeddingLiteral,
                                   int topK,
                                   List<Long> documentIds,
                                   List<String> fileTypes,
                                   String documentNameKeyword,
                                   Long ownerUserId) {
        return new SearchRequest(kbId, effectiveQuery, embeddingLiteral, topK,
                documentIds, fileTypes, documentNameKeyword, ownerUserId);
    }
}