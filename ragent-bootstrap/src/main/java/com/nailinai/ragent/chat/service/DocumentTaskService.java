package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.entity.DocumentTask;

/**
 * 文档异步入库任务服务。
 * 负责任务的创建、查询和异步执行，是文档索引从同步改为异步的核心入口。
 */
public interface DocumentTaskService {

    /**
     * 创建一条异步入库任务记录。
     * 在异步执行之前调用，先在数据库中持久化任务信息，确保即使服务重启任务也不会丢失。
     *
     * @param docId 文档 ID
     * @param kbId  知识库 ID
     * @return 创建的任务对象，id 已被自动回填
     */
    DocumentTask createTask(Long docId, Long kbId);

    /**
     * 按任务 ID 查询任务详情，前端轮询时使用。
     *
     * @param taskId 任务 ID
     * @return 任务对象，不存在时抛出 BusinessException
     */
    DocumentTask getTask(Long taskId);

    /**
     * 按文档 ID 查询最新一条任务记录，用于判断文档当前是否有正在执行的任务。
     *
     * @param docId 文档 ID
     * @return 最新的任务对象，不存在时返回 null
     */
    DocumentTask getLatestTaskByDocId(Long docId);

    /**
     * 异步执行文档入库流程。
     * 使用 @Async 提交到虚拟线程池，调用方不会阻塞。
     * 执行过程中会逐步更新任务的 progress（PARSING → CHUNKING → EMBEDDING → INDEXING），
     * 成功时标记 status=SUCCESS，失败时标记 status=FAILED 并记录 error_message。
     *
     * @param taskId       任务 ID
     * @param docId        文档 ID
     * @param forceReindex 是否强制重建索引（即使文档已 INDEXED）
     */
    void executeAsync(Long taskId, Long docId, boolean forceReindex);

    /**
     * 扫描并重试失败或卡住的任务。
     * 由定时任务调用，每分钟执行一次：
     * 1. FAILED 且 retry_count < MAX_RETRY 的任务自动重试
     * 2. RUNNING/PENDING 且超过 STALE_THRESHOLD 未更新的任务（服务重启后卡住）恢复执行
     *
     * @return 本次触发的重试任务数量
     */
    int retryStaleTasks();
}
