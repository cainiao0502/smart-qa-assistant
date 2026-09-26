package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.entity.Document;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.entity.DocumentTask;
import com.nailinai.ragent.enums.DocumentStatus;
import com.nailinai.ragent.enums.TaskProgress;
import com.nailinai.ragent.enums.TaskStatus;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.DocumentTaskMapper;
import com.nailinai.ragent.chat.service.DocumentParser;
import com.nailinai.ragent.chat.service.DocumentTaskService;
import com.nailinai.ragent.chat.retrieve.KeywordTokenizer;
import com.nailinai.ragent.infra.embedding.EmbeddingClient;
import com.nailinai.ragent.chat.service.TextChunker;
import com.nailinai.ragent.framework.util.TokenEstimateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档异步入库任务服务实现。
 * 将原来 DocumentIndexServiceImpl 中的同步入库流程拆分为四个步骤，
 * 每个步骤之间更新任务进度，供前端轮询展示。
 * 核心方法 executeAsync 使用 @Async 在虚拟线程中执行，调用方不会阻塞。
 */
@Slf4j
@Service
public class DocumentTaskServiceImpl implements DocumentTaskService {

    private final DocumentTaskMapper documentTaskMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    /** 编程式事务模板，用于在异步线程中手动控制事务边界 */
    private final TransactionTemplate transactionTemplate;
    /** 期望的向量维度，对应 document_chunk.embedding 的 VECTOR(维度) 定义 */
    private final int embeddingDim;

    /**
     * 自身代理。retryStaleTasks 里必须经代理调用 executeAsync 才能触发 @Async；
     * 直接 this 调用会绕过切面，让重试任务同步阻塞在调度线程上。
     * 单元测试直接 new 时该字段为 null，走降级路径。
     */
    @Autowired
    @Lazy
    private DocumentTaskService self;

    public DocumentTaskServiceImpl(DocumentTaskMapper documentTaskMapper,
                                   DocumentMapper documentMapper,
                                   DocumentChunkMapper documentChunkMapper,
                                   DocumentParser documentParser,
                                   TextChunker textChunker,
                                   EmbeddingClient embeddingClient,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.rag.embedding-dim:1024}") int embeddingDim) {
        this.documentTaskMapper = documentTaskMapper;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.embeddingDim = Math.max(1, embeddingDim);
    }

    @Override
    public DocumentTask createTask(Long docId, Long kbId) {
        DocumentTask task = new DocumentTask();
        task.setDocId(docId);
        task.setKbId(kbId);
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(TaskProgress.PENDING);
        task.setErrorMessage(null);
        task.setRetryCount(0);
        // insert 后自增主键自动回填到 task.id
        documentTaskMapper.insert(task);
        return task;
    }

    @Override
    public DocumentTask getTask(Long taskId) {
        DocumentTask task = documentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "task not found");
        }
        return task;
    }

    @Override
    public DocumentTask getLatestTaskByDocId(Long docId) {
        return documentTaskMapper.selectLatestByDocId(docId);
    }

    /**
     * 异步执行文档入库的核心方法。
     * 被 @Async("documentTaskExecutor") 标注，Spring 会将此方法提交到虚拟线程池执行，
     * Controller 调用后立即返回，方法体在独立线程中运行。
     *
     * 执行流程：解析(PARSING) → 切分(CHUNKING) → 向量化(EMBEDDING) → 写入(INDEXING)
     * 每个步骤完成后更新 document_task 表的 progress 字段，前端可据此展示进度。
     * 成功时标记 status=SUCCESS，失败时标记 status=FAILED 并记录 error_message。
     */
    @Async("documentTaskExecutor")
    @Override
    public void executeAsync(Long taskId, Long docId, boolean forceReindex) {
        // 校验文档存在性
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            failTask(taskId, "document not found");
            return;
        }
        // 如果文档已索引且不强制重建，直接标记任务成功
        if (DocumentStatus.INDEXED.equals(document.getStatus()) && !forceReindex) {
            completeTask(taskId);
            return;
        }

        // 标记文档为 INDEXING，任务为 RUNNING + PARSING。
        // 注意保留已有 content：DocumentMapper.update 是无条件覆盖写，
        // 若此处置 null 而后续解析失败，原本详情页可读的全文会被永久清空。
        updateDocumentStatus(document, document.getContent(), DocumentStatus.INDEXING);
        updateTask(taskId, TaskStatus.RUNNING, TaskProgress.PARSING, null);

        // 耗时追踪
        long parseDurationMs = 0;
        long chunkDurationMs = 0;
        long embedDurationMs = 0;
        long indexDurationMs = 0;

        String content = null;
        try {
            // 步骤1：用 Tika 解析文档提取纯文本
            long parseStart = System.currentTimeMillis();
            content = documentParser.parse(new File(document.getStoragePath()));
            parseDurationMs = System.currentTimeMillis() - parseStart;
            log.info("Document {} parsed, content length: {}, took {}ms", docId, content != null ? content.length() : 0, parseDurationMs);

            // 步骤2：按语义切分文本为 chunk
            updateTask(taskId, TaskStatus.RUNNING, TaskProgress.CHUNKING, null, parseDurationMs, null, null, null);
            long chunkStart = System.currentTimeMillis();
            List<String> chunks = textChunker.split(content);
            chunkDurationMs = System.currentTimeMillis() - chunkStart;
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "document content is empty after parsing");
            }
            log.info("Document {} chunked into {} pieces, took {}ms", docId, chunks.size(), chunkDurationMs);

            // 步骤3：调用 embedding 模型为每个 chunk 生成向量（最耗时步骤）
            updateTask(taskId, TaskStatus.RUNNING, TaskProgress.EMBEDDING, null, parseDurationMs, chunkDurationMs, null, null);
            long embedStart = System.currentTimeMillis();
            List<List<Float>> embeddings = embeddingClient.embedBatch(chunks);
            embedDurationMs = System.currentTimeMillis() - embedStart;
            if (embeddings.size() != chunks.size()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "embedding size does not match chunk size");
            }
            // 维度守卫：document_chunk.embedding 是固定维度向量列。换 embedding 模型导致
            // 维度不符时，与其让批量插入抛裸 SQL 错误（还被重试 3 次白白烧掉配额），
            // 不如在此快速失败并给出可操作提示。
            int actualDim = embeddings.get(0).size();
            if (actualDim != embeddingDim) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "embedding dimension mismatch: model returned " + actualDim
                                + " but document_chunk.embedding expects " + embeddingDim
                                + ". Switch the embedding model back, or migrate the column and re-index all documents.");
            }
            log.info("Document {} embedded {} chunks, took {}ms", docId, embeddings.size(), embedDurationMs);

            // 步骤4：在事务中删除旧 chunk 并批量插入新 chunk，最后更新文档状态为 INDEXED
            updateTask(taskId, TaskStatus.RUNNING, TaskProgress.INDEXING, null, parseDurationMs, chunkDurationMs, embedDurationMs, null);
            long indexStart = System.currentTimeMillis();
            String finalContent = content;
            transactionTemplate.executeWithoutResult(status -> {
                documentChunkMapper.deleteByDocId(docId);
                documentChunkMapper.batchInsert(buildChunks(document, chunks, embeddings));
                updateDocumentStatus(document, finalContent, DocumentStatus.INDEXED);
            });
            indexDurationMs = System.currentTimeMillis() - indexStart;
            log.info("Document {} indexed successfully, took {}ms", docId, indexDurationMs);

            completeTask(taskId, parseDurationMs, chunkDurationMs, embedDurationMs, indexDurationMs);
        } catch (Exception ex) {
            // 异步方法中异常无法传递到 Controller，改为写入 error_message 供前端读取
            log.error("Document {} indexing failed", docId, ex);
            failDocumentStatus(document, content, ex.getMessage() != null ? ex.getMessage() : "unknown error");
            failTask(taskId, ex.getMessage() != null ? ex.getMessage() : "unknown error",
                    parseDurationMs, chunkDurationMs, embedDurationMs, indexDurationMs);
        }
    }

    /** 更新任务的状态、进度和错误信息 */
    private void updateTask(Long taskId, TaskStatus status, TaskProgress progress, String errorMessage) {
        updateTask(taskId, status, progress, errorMessage, null, null, null, null);
    }

    /** 更新任务的状态、进度、错误信息和各步骤耗时 */
    private void updateTask(Long taskId, TaskStatus status, TaskProgress progress, String errorMessage,
                            Long parseDurationMs, Long chunkDurationMs, Long embedDurationMs, Long indexDurationMs) {
        DocumentTask task = new DocumentTask();
        task.setId(taskId);
        task.setStatus(status);
        task.setProgress(progress);
        task.setErrorMessage(errorMessage);
        task.setParseDurationMs(parseDurationMs);
        task.setChunkDurationMs(chunkDurationMs);
        task.setEmbedDurationMs(embedDurationMs);
        task.setIndexDurationMs(indexDurationMs);
        documentTaskMapper.update(task);
    }

    /** 标记任务成功，progress 保留在最后一步 INDEXING */
    private void completeTask(Long taskId) {
        updateTask(taskId, TaskStatus.SUCCESS, TaskProgress.INDEXING, null);
    }

    /** 标记任务成功，附带各步骤耗时 */
    private void completeTask(Long taskId, long parseDurationMs, long chunkDurationMs, long embedDurationMs, long indexDurationMs) {
        updateTask(taskId, TaskStatus.SUCCESS, TaskProgress.INDEXING, null,
                parseDurationMs, chunkDurationMs, embedDurationMs, indexDurationMs);
    }

    /** 标记任务失败，progress 重置为 PENDING，记录错误信息 */
    private void failTask(Long taskId, String errorMessage) {
        updateTask(taskId, TaskStatus.FAILED, TaskProgress.PENDING, errorMessage);
    }

    /** 标记任务失败，附带已完成的步骤耗时 */
    private void failTask(Long taskId, String errorMessage,
                          long parseDurationMs, long chunkDurationMs, long embedDurationMs, long indexDurationMs) {
        updateTask(taskId, TaskStatus.FAILED, TaskProgress.PENDING, errorMessage,
                parseDurationMs, chunkDurationMs, embedDurationMs, indexDurationMs);
    }

    /** 更新文档的解析内容和状态 */
    private void updateDocumentStatus(Document document, String content, DocumentStatus status) {
        document.setContent(content);
        document.setStatus(status);
        document.setErrorMessage(null);
        documentMapper.update(document);
    }

    /** 更新文档状态为失败，并记录错误信息 */
    private void failDocumentStatus(Document document, String content, String errorMessage) {
        document.setContent(content);
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(errorMessage);
        documentMapper.update(document);
    }

    /** 将切分结果和向量组装为 DocumentChunk 列表，用于批量插入 */
    private List<DocumentChunk> buildChunks(Document document, List<String> chunks, List<List<Float>> embeddings) {
        List<DocumentChunk> results = new ArrayList<>();
        int paragraphCounter = 0;
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setKbId(document.getKbId());
            chunk.setDocId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setChunkText(chunks.get(i));
            // 分词契约：与查询侧共用 KeywordTokenizer，tsv 生成列据此建 GIN 索引
            chunk.setChunkTokens(KeywordTokenizer.toTokenString(chunks.get(i)));
            chunk.setTokenEstimate(TokenEstimateUtils.estimate(chunks.get(i)));
            chunk.setParagraphIndex(paragraphCounter);
            chunk.setEmbeddingLiteral(toVectorLiteral(embeddings.get(i)));
            results.add(chunk);
            paragraphCounter += countParagraphs(chunks.get(i));
        }
        return results;
    }

    private int countParagraphs(String text) {
        if (text == null || text.isBlank()) return 1;
        int count = 1;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                count++;
            }
        }
        return Math.max(1, count);
    }

    /** 将 float 列表转为 pgvector 可接受的字面量格式，如 [0.1,0.2,0.3] */
    private String toVectorLiteral(List<Float> embedding) {
        return embedding.stream()
                .map(value -> Float.isFinite(value) ? String.valueOf(value) : "0.0")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static final int MAX_RETRY = 3;
    private static final int STALE_MINUTES = 5;
    private static final int BATCH_LIMIT = 10;

    @Override
    public int retryStaleTasks() {
        java.time.LocalDateTime staleThreshold = java.time.LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<DocumentTask> candidates = documentTaskMapper.selectRetryCandidates(MAX_RETRY, staleThreshold, BATCH_LIMIT);
        if (candidates.isEmpty()) {
            return 0;
        }
        int triggered = 0;
        for (DocumentTask task : candidates) {
            try {
                // 原子认领：只有仍处于 FAILED(未超限) 或 已停滞 的任务才会被重置为 PENDING。
                // 多轮调度/多实例并发触发时只有一个调用方能认领成功，避免同一文档双跑。
                int claimed = documentTaskMapper.resetForRetry(task.getId(), staleThreshold, MAX_RETRY);
                if (claimed == 0) {
                    continue;
                }
                // 必须经代理调用：直接 this.executeAsync 会绕过 @Async 切面，
                // 重试任务会同步阻塞在调度线程上，而不是进入 documentTaskExecutor。
                if (self != null) {
                    self.executeAsync(task.getId(), task.getDocId(), true);
                } else {
                    executeAsync(task.getId(), task.getDocId(), true);
                }
                triggered++;
                log.info("Retry triggered for task {}: docId={}, retryCount={}", task.getId(), task.getDocId(), task.getRetryCount());
            } catch (Exception ex) {
                log.error("Failed to trigger retry for task {}", task.getId(), ex);
            }
        }
        return triggered;
    }
}
