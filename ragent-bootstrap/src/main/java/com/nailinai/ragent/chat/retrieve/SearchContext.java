package com.nailinai.ragent.chat.retrieve;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次检索的上下文。
 *
 * <p>{@code flags} 是本次检索内的事件标记（可变）：后处理器是无状态单例，
 * 链内发生的「跨处理器才有意义」的状态（如 cross-encoder 是否实际生效）挂在
 * 每次请求自己的 context 上，而不是处理器成员变量——并发安全，且评估链与生产链
 * 天然隔离。消费方：ThresholdFilterPostProcessor 据此选择融合分阈值的量纲。</p>
 */
public record SearchContext(
        String originalQuery,
        String effectiveQuery,
        int topK,
        double scoreThreshold,
        Set<String> flags
) {
    /** cross-encoder 实际生效（成功拿到重排分）标记：由 RerankPostProcessor 设置 */
    public static final String FLAG_CROSS_ENCODER = "cross-encoder";

    public static SearchContext of(String originalQuery,
                                   String effectiveQuery,
                                   int topK,
                                   double scoreThreshold) {
        return new SearchContext(originalQuery, effectiveQuery, topK, scoreThreshold, new LinkedHashSet<>());
    }

    public void addFlag(String flag) {
        flags.add(flag);
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }
}
