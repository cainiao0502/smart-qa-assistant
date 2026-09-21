package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.Document;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.enums.DocumentStatus;
import com.nailinai.ragent.entity.DocumentTask;
import com.nailinai.ragent.enums.TaskStatus;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.DocumentTaskMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.user.context.UserIdHolder;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.chat.service.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class DocumentServiceImpl implements DocumentService {

    private final FileStorageService fileStorageService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentTaskMapper documentTaskMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public DocumentServiceImpl(FileStorageService fileStorageService,
                               DocumentMapper documentMapper,
                               DocumentChunkMapper documentChunkMapper,
                               DocumentTaskMapper documentTaskMapper,
                               KnowledgeBaseMapper knowledgeBaseMapper) {
        this.fileStorageService = fileStorageService;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentTaskMapper = documentTaskMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public DocumentResponse upload(Long kbId, MultipartFile file) {
        Long userId = com.nailinai.ragent.user.context.UserContext.currentUserId();
        if (knowledgeBaseMapper.selectByIdAndOwner(kbId, userId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "file is required");
        }

        String storagePath = null;
        Document document = new Document();
        try {
            storagePath = fileStorageService.save(file);
            document.setKbId(kbId);
            document.setName(resolveFilename(file.getOriginalFilename()));
            document.setFileType(resolveFileType(file.getOriginalFilename()));
            document.setStoragePath(storagePath);
            document.setContent(null);
            document.setStatus(DocumentStatus.UPLOADED);
            documentMapper.insert(document);
            return toResponse(document);
        } catch (RuntimeException ex) {
            if (StringUtils.hasText(storagePath)) {
                fileStorageService.delete(storagePath);
            }
            throw ex;
        }
    }

    @Override
    public List<DocumentResponse> listByKbId(Long kbId) {
        return documentMapper.selectByKbId(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 按文档 ID 查询文档实体。
     * 异步入库流程中 DocumentController 通过此方法获取文档的 kbId，
     * 用于创建 DocumentTask 记录。
     */
    @Override
    public Document getById(Long docId) {
        Document document = requireAccessibleDocument(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        return document;
    }

    @Override
    public DocumentDetailResponse getDetail(Long docId) {
        Document document = requireAccessibleDocument(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        List<DocumentChunk> chunks = documentChunkMapper.selectByDocId(docId);
        List<DocumentDetailResponse.ChunkItem> chunkItems = chunks.stream()
                .map(c -> DocumentDetailResponse.ChunkItem.builder()
                        .chunkIndex(c.getChunkIndex())
                        .chunkText(c.getChunkText())
                        .tokenEstimate(c.getTokenEstimate())
                        .paragraphIndex(c.getParagraphIndex())
                        .build())
                .toList();
        return DocumentDetailResponse.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .name(document.getName())
                .fileType(document.getFileType())
                .status(document.getStatus())
                .chunkCount(document.getChunkCount())
                .createdAt(document.getCreatedAt())
                .content(document.getContent())
                .chunks(chunkItems)
                .build();
    }

    @Override
    @Transactional
    public void delete(Long docId) {
        Document document = requireAccessibleDocument(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        // 竞态守卫：存在进行中/待执行/仍可被重试调度的入库任务时拒绝删除。
        // FAILED 且 retry_count 未耗尽的任务仍可能被 retryStaleTasks 原子认领转 PENDING
        // 后重新执行，把 chunk 重新批量插入已删文档（孤儿行）；
        // 重试额度已耗尽的 FAILED 任务不会再被认领，可安全删除。
        DocumentTask latestTask = documentTaskMapper.selectLatestByDocId(docId);
        boolean taskMayStillRun = latestTask != null && (
                latestTask.getStatus() == TaskStatus.RUNNING
                        || latestTask.getStatus() == TaskStatus.PENDING
                        || (latestTask.getStatus() == TaskStatus.FAILED
                                && latestTask.getRetryCount() != null
                                && latestTask.getRetryCount() < 3));
        if (taskMayStillRun) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "document has an indexing task in progress, try again later");
        }
        documentChunkMapper.deleteByDocId(docId);
        documentTaskMapper.deleteByDocId(docId);
        documentMapper.deleteById(docId);
        // 文件删除移到事务提交后执行：若先删文件而事务回滚，文档记录还在但文件已丢，
        // 该文档将永久无法重新入库。无事务上下文的调用方（理论不应有）直接删，兜底。
        String storagePath = document.getStoragePath();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileStorageService.delete(storagePath);
                }
            });
        } else {
            fileStorageService.delete(storagePath);
        }
    }

    /**
     * 取文档并校验归属：只有所属知识库的所有者才能读到它。
     *
     * <p>为什么必须在这里做：文档的读接口（详情、触发入库、删除）此前只用 {@code selectById(docId)}，
     * 只要知道 docId 就能读到别人的知识库内容、甚至删掉别人的文档——而知识库入口
     * （上传、删除知识库）是校验了 owner 的，等于数据出口比入口更松。</p>
     *
     * <p><b>没有登录上下文的内部调用直接放行</b>（例如知识库级联删除、离线评估）：
     * 这些路径的归属由上游负责，其中级联删除在删知识库前已经校验过 owner。
     * 用户请求一定会带 userId——它要么在请求线程里取自 Sa-Token，
     * 要么由 {@code ChatController} 预先绑定到异步线程（见 {@code UserIdHolder}）。</p>
     */
    private Document requireAccessibleDocument(Long docId) {
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        Long userId = UserIdHolder.get();
        if (userId == null) {
            return document;
        }
        if (knowledgeBaseMapper.selectByIdAndOwner(document.getKbId(), userId) == null) {
            // 对外一律返回「不存在」，不暴露「这个文档存在但不属于你」
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        return document;
    }

    private String resolveFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unknown";
        }
        return filename;
    }

    private String resolveFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private DocumentResponse toResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .name(document.getName())
                .fileType(document.getFileType())
                .status(document.getStatus())
                .chunkCount(document.getChunkCount())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
