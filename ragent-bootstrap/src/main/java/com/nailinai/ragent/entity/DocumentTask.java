package com.nailinai.ragent.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import com.nailinai.ragent.enums.TaskProgress;
import com.nailinai.ragent.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档异步入库任务实体。
 * 每次用户触发文档索引时创建一条任务记录，用于追踪异步处理的状态和进度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentTask extends BaseEntity {

    /** 关联的文档 ID */
    private Long docId;

    /** 关联的知识库 ID */
    private Long kbId;

    /** 任务整体状态：PENDING / RUNNING / SUCCESS / FAILED */
    private TaskStatus status;

    /** 当前执行到的步骤：PENDING / PARSING / CHUNKING / EMBEDDING / INDEXING */
    private TaskProgress progress;

    /** 失败时的错误信息，成功时为 null */
    private String errorMessage;

    /** 解析步骤耗时（毫秒） */
    private Long parseDurationMs;

    /** 切分步骤耗时（毫秒） */
    private Long chunkDurationMs;

    /** 向量化步骤耗时（毫秒） */
    private Long embedDurationMs;

    /** 写入步骤耗时（毫秒） */
    private Long indexDurationMs;
}
