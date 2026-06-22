package com.nailinai.ragent.enums;

/**
 * 异步入库任务的执行进度步骤。
 * PENDING → PARSING → CHUNKING → EMBEDDING → INDEXING
 * 比 TaskStatus 更细粒度，前端可据此展示"正在切分文本"等进度提示。
 */
public enum TaskProgress {
    /** 待执行 */
    PENDING,
    /** 正在用 Tika 解析文档提取纯文本 */
    PARSING,
    /** 正在按固定长度切分文本为 chunk */
    CHUNKING,
    /** 正在调用 embedding 模型为 chunk 生成向量 */
    EMBEDDING,
    /** 正在将 chunk 和向量写入 document_chunk 表 */
    INDEXING
}
