package com.nailinai.ragent.chat.retrieve;

import java.util.List;

/**
 * 一次检索请求。
 *
 * <p><b>归属过滤契约（fail-closed）：</b>{@code ownerUserId} 必填，{@code null} 在
 * {@link #of} 处直接抛异常——归属条件在 SQL 层恒生效，绝不因「内部调用」而放开。
 * 旧契约允许 null 表示内部调用（离线评估）跳过归属过滤，这是文档化的 fail-open：
 * 一旦上游身份传播断链（异步线程丢上下文，chat_message.owner_user_id 为 NULL 的
 * 同类故障），检索会静默跨租户读取而不是报错。</p>
 *
 * <p>调用方按路径提供 owner：</p>
 * <ul>
 *   <li>chat / agent 循环：当前登录用户，经 {@code UserIdHolder} 三步约定传播；</li>
 *   <li>离线评估、管理端检索调试：无登录上下文或需跨库访问，按 kbId 解析出
 *       knowledge_base.owner_user_id 后经 {@code UserIdHolder.set} 显式绑定（finally 中 clear）。</li>
 * </ul>
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
        if (ownerUserId == null) {
            // fail-fast：放行 null 等于把「身份传播断链」静默降级为跨租户读取。
            // 评估/调试路径须先解析库 owner 并 UserIdHolder.set 绑定，见类注释。
            throw new IllegalArgumentException(
                    "ownerUserId is required for retrieval (fail-closed)。"
                            + "chat 路径检查 UserIdHolder 传播（capture/set/clear 三步约定）；"
                            + "评估/调试等离线路径须按 kbId 解析 knowledge_base.owner_user_id 并显式绑定。");
        }
        return new SearchRequest(kbId, effectiveQuery, embeddingLiteral, topK,
                documentIds, fileTypes, documentNameKeyword, ownerUserId);
    }
}
