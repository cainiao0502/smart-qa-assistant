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
}
