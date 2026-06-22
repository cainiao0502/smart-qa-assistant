package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.DocumentTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
                parse_duration_ms, chunk_duration_ms, embed_duration_ms, index_duration_ms)
            VALUES (#{docId}, #{kbId}, #{status}, #{progress}, #{errorMessage},
                #{parseDurationMs}, #{chunkDurationMs}, #{embedDurationMs}, #{indexDurationMs})
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
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_task
            WHERE doc_id = #{docId}
            ORDER BY id DESC
            LIMIT 1
            """)
    DocumentTask selectLatestByDocId(Long docId);

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

    @Delete("""
            DELETE FROM document_task
            WHERE doc_id = #{docId}
            """)
    int deleteByDocId(Long docId);
}
