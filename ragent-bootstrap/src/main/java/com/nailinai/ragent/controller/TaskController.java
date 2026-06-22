package com.nailinai.ragent.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.response.TaskResponse;
import com.nailinai.ragent.entity.DocumentTask;
import com.nailinai.ragent.chat.service.DocumentTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步入库任务查询接口。
 * 前端通过此接口轮询任务状态，获取当前进度和错误信息。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final DocumentTaskService documentTaskService;

    public TaskController(DocumentTaskService documentTaskService) {
        this.documentTaskService = documentTaskService;
    }

    /**
     * 按任务 ID 查询任务状态。
     * 前端在触发文档索引后，每隔 1.5 秒调用此接口轮询，直到 status 为 SUCCESS 或 FAILED。
     *
     * @param taskId 任务 ID（由 POST /api/documents/{docId}/index 返回）
     * @return 任务状态信息，包含 status、progress、errorMessage 等
     */
    @GetMapping("/{taskId}")
    public Result<TaskResponse> getTask(@PathVariable Long taskId) {
        DocumentTask task = documentTaskService.getTask(taskId);
        return Result.success(toResponse(task));
    }

    /** 将 DocumentTask 实体转换为 TaskResponse DTO */
    private TaskResponse toResponse(DocumentTask task) {
        return TaskResponse.builder()
                .id(task.getId())
                .docId(task.getDocId())
                .kbId(task.getKbId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .errorMessage(task.getErrorMessage())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
