package com.nailinai.ragent.enums;

/**
 * 异步入库任务的整体状态。
 * PENDING → RUNNING → SUCCESS / FAILED
 */
public enum TaskStatus {
    /** 待执行，任务已创建但尚未开始 */
    PENDING,
    /** 执行中，异步线程正在处理文档 */
    RUNNING,
    /** 执行成功，文档已完成索引 */
    SUCCESS,
    /** 执行失败，error_message 中记录了失败原因 */
    FAILED
}
