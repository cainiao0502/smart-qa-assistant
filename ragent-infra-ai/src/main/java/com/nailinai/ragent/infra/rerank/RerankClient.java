package com.nailinai.ragent.infra.rerank;

import java.util.List;

/**
 * 重排序（cross-encoder reranker）客户端。
 *
 * <p>与检索阶段的启发式重排（RRF + 词法/语义加权）不同，交叉编码器把
 * 「query + 候选文本」成对送入模型打分，能显著提升精排质量——这是当前
 * 主流 RAG 系统（RAGFlow/Dify 等）的标配环节。
 *
 * <p>本接口是<b>可选能力</b>：未配置或调用失败时，调用方必须能优雅降级回
 * 启发式重排，不能阻塞检索主链路（与 {@link com.nailinai.ragent.infra.vision.VisionClient}
 * 的「不可用」语义一致）。
 */
public interface RerankClient {

    /** 是否可用（配置了供应商且密钥齐全） */
    boolean isAvailable();

    /** 供应商名（用于日志与追踪） */
    String name();

    /**
     * 对候选文档按与 query 的相关度打分。
     *
     * @param query     查询文本（通常为改写后的 effectiveQuery）
     * @param documents 候选文本列表，顺序即索引顺序
     * @param topN      最多返回前 N 个结果（0 表示全部）
     * @return 打分结果列表，按 index 对应入参 documents 的下标
     * @throws IllegalStateException 供应商不可用或调用/解析失败
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);

    /** 单条打分结果：index 对应入参 documents 下标，score 为相关度分 */
    record RerankResult(int index, double score) {
    }

    /** 未配置时的占位实现：isAvailable=false，调用直接抛出（调用方应先判 isAvailable） */
    static RerankClient unavailable() {
        return new RerankClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String name() {
                return "rerank-unavailable";
            }

            @Override
            public List<RerankResult> rerank(String query, List<String> documents, int topN) {
                throw new IllegalStateException("rerank client is not configured");
            }
        };
    }
}
