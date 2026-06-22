package com.nailinai.ragent.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.dto.response.TaskResponse;
import com.nailinai.ragent.entity.Document;
import com.nailinai.ragent.entity.DocumentTask;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.chat.service.DocumentTaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口。
 * 提供文档上传、列表查询、异步入库触发和删除功能。
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentTaskService documentTaskService;

    public DocumentController(DocumentService documentService,
                              DocumentTaskService documentTaskService) {
        this.documentService = documentService;
        this.documentTaskService = documentTaskService;
    }

    /** 上传文档到指定知识库 */
    @PostMapping("/kb/{kbId}/documents/upload")
    public Result<DocumentResponse> upload(@PathVariable Long kbId, @RequestPart("file") MultipartFile file) {
        return Result.success(documentService.upload(kbId, file));
    }

    /** 查询指定知识库下的文档列表 */
    @GetMapping("/kb/{kbId}/documents")
    public Result<List<DocumentResponse>> list(@PathVariable Long kbId) {
        return Result.success(documentService.listByKbId(kbId));
    }

    /** 查询文档详情，包含原文内容和切片列表 */
    @GetMapping("/documents/{docId}")
    public Result<DocumentDetailResponse> getDetail(@PathVariable Long docId) {
        return Result.success(documentService.getDetail(docId));
    }

    /**
     * 触发文档异步入库。
     * 改造前是同步阻塞接口，改造后立即返回任务 ID，入库流程在虚拟线程中异步执行。
     * 前端通过 GET /api/tasks/{taskId} 轮询任务状态和进度。
     *
     * @param docId        文档 ID
     * @param forceReindex 是否强制重建索引（即使文档已 INDEXED），默认 false
     * @return 任务信息，包含 taskId、status、progress 等
     */
    @PostMapping("/documents/{docId}/index")
    public Result<TaskResponse> index(@PathVariable Long docId,
                                      @RequestParam(defaultValue = "false") boolean forceReindex) {
        // 校验文档存在并获取 kbId
        Document document = documentService.getById(docId);
        // 创建任务记录，拿到 taskId
        DocumentTask task = documentTaskService.createTask(docId, document.getKbId());
        // 提交异步执行（不会阻塞当前线程）
        documentTaskService.executeAsync(task.getId(), docId, forceReindex);
        // 立即返回任务信息，前端据此轮询
        return Result.success(TaskResponse.builder()
                .id(task.getId())
                .docId(task.getDocId())
                .kbId(task.getKbId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .errorMessage(task.getErrorMessage())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build());
    }

    /** 删除文档及其关联的 chunk 数据 */
    @DeleteMapping("/documents/{docId}")
    public Result<Void> delete(@PathVariable Long docId) {
        documentService.delete(docId);
        return Result.success();
    }
}
