package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.DocumentTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档异步入库任务的数据库操作接口。
 */
@Mapper
public interface DocumentTaskMapper {

    /**
     * 插入一条任务记录。
     * 插入后自增主键会自动回填到 task.id，供调用方获取 taskId。
     */
    @Insert("""
            INSERT INTO document_task (doc_id, kb_id, status, progress, error_message,
                parse_duration_ms, chunk_duration_ms, embed_duration_ms, index_duration_ms, retry_count)
            VALUES (#{docId}, #{kbId}, #{status}, #{progress}, #{errorMessage},
                #{parseDurationMs}, #{chunkDurationMs}, #{embedDurationMs}, #{indexDurationMs}, #{retryCount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentTask task);

    /**
     * 按主键查询任务，前端轮询任务状态时使用。
     */
    @Select("""
            SELECT id, doc_id AS docId, kb_id AS kbId, status, progress,
                   error_message AS errorMessage,
                   parse_duration_ms AS parseDurationMs,
                   chunk_duration_ms AS chunkDurationMs,
                   embed_duration_ms AS embedDurationMs,
                   index_duration_ms AS indexDurationMs,
                   retry_count AS retryCount,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_task
            WHERE id = #{id}
            """)
    DocumentTask selectById(Long id);

    /**
     * 按文档 ID 查询最新一条任务记录，用于判断文档当前是否有正在执行的任务。
     */
    @Select("""
            SELECT id, doc_id AS docId, kb_id AS kbId, status, progress,
                   error_message AS errorMessage,
                   parse_duration_ms AS parseDurationMs,
                   chunk_duration_ms AS chunkDurationMs,
                   embed_duration_ms AS embedDurationMs,
                   index_duration_ms AS indexDurationMs,
                   retry_count AS retryCount,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_task
            WHERE doc_id = #{docId}
            ORDER BY id DESC
            LIMIT 1
            """)
    DocumentTask selectLatestByDocId(Long docId);

    /**
     * 查询需要重试或恢复的任务：
     * 1. FAILED 且 retry_count < maxRetry（自动重试）
     * 2. RUNNING/PENDING 且 updated_at 早于阈值（服务重启后卡住的任务恢复）
     */
    @Select("""
            SELECT id, doc_id AS docId, kb_id AS kbId, status, progress,
                   error_message AS errorMessage,
                   parse_duration_ms AS parseDurationMs,
                   chunk_duration_ms AS chunkDurationMs,
                   embed_duration_ms AS embedDurationMs,
                   index_duration_ms AS indexDurationMs,
                   retry_count AS retryCount,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_task
            WHERE (status = 'FAILED' AND retry_count < #{maxRetry})
               OR (status IN ('RUNNING', 'PENDING') AND updated_at < #{staleThreshold})
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<DocumentTask> selectRetryCandidates(
            @org.apache.ibatis.annotations.Param("maxRetry") int maxRetry,
            @org.apache.ibatis.annotations.Param("staleThreshold") java.time.LocalDateTime staleThreshold,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 更新任务的状态、进度和错误信息。
     * 异步执行过程中每完成一个步骤都会调用此方法更新进度。
     */
    @Update("""
            UPDATE document_task
            SET status = #{status},
                progress = #{progress},
                error_message = #{errorMessage},
                parse_duration_ms = #{parseDurationMs},
                chunk_duration_ms = #{chunkDurationMs},
                embed_duration_ms = #{embedDurationMs},
                index_duration_ms = #{indexDurationMs},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(DocumentTask task);

    /**
     * 原子认领一个可重试的任务：重置为 PENDING 并递增 retry_count。
     *
     * <p>WHERE 条件与 selectRetryCandidates 的筛选一致：FAILED 且未超重试上限，
     * 或 RUNNING/PENDING 且已停滞。并发触发时只有一个调用方能认领成功（返回 1），
     * 状态已被其他调度方改变的任务返回 0，避免同一文档被两个执行流同时删建 chunk。
     */
    @Update("""
            UPDATE document_task
            SET status = 'PENDING',
                progress = 'PENDING',
                error_message = null,
                retry_count = retry_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ((status = 'FAILED' AND retry_count < #{maxRetry})
                OR (status IN ('RUNNING', 'PENDING') AND updated_at < #{staleThreshold}))
            """)
    int resetForRetry(Long id,
                      @org.apache.ibatis.annotations.Param("staleThreshold") java.time.LocalDateTime staleThreshold,
                      @org.apache.ibatis.annotations.Param("maxRetry") int maxRetry);

    @Delete("""
            DELETE FROM document_task
            WHERE doc_id = #{docId}
            """)
    int deleteByDocId(Long docId);
}
