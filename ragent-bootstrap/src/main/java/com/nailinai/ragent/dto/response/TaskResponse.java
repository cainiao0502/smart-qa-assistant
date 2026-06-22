package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.enums.TaskProgress;
import com.nailinai.ragent.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步入库任务的响应 DTO，返回给前端用于轮询任务状态和展示进度。
 */
@Data
@Builder
public class TaskResponse {

    /** 任务 ID，前端通过此 ID 轮询 GET /api/tasks/{taskId} */
    private Long id;

    /** 关联的文档 ID */
    private Long docId;

    /** 关联的知识库 ID */
    private Long kbId;

    /** 任务整体状态：PENDING / RUNNING / SUCCESS / FAILED */
    private TaskStatus status;

    /** 当前执行步骤：PENDING / PARSING / CHUNKING / EMBEDDING / INDEXING */
    private TaskProgress progress;

    /** 失败时的错误信息，前端可据此展示具体失败原因 */
    private String errorMessage;

    /** 任务创建时间 */
    private LocalDateTime createdAt;

    /** 任务最后更新时间，可用于判断任务是否卡死 */
    private LocalDateTime updatedAt;
}
