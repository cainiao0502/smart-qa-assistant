package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface DocumentMapper {

    @Insert("""
            INSERT INTO document (kb_id, name, file_type, storage_path, content, status, error_message)
            VALUES (#{kbId}, #{name}, #{fileType}, #{storagePath}, #{content}, #{status}, #{errorMessage})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Document document);

    @Select("""
            SELECT d.id, d.kb_id AS kbId, d.name, d.file_type AS fileType, d.storage_path AS storagePath,
                   d.content, d.status, d.error_message AS errorMessage,
                   d.created_at AS createdAt, d.updated_at AS updatedAt,
                   COUNT(dc.id) AS chunkCount
            FROM document d
            LEFT JOIN document_chunk dc ON dc.doc_id = d.id
            WHERE d.id = #{id}
            GROUP BY d.id
            """)
    Document selectById(Long id);

    @Select("""
            SELECT d.id, d.kb_id AS kbId, d.name, d.file_type AS fileType, d.storage_path AS storagePath,
                   d.content, d.status, d.error_message AS errorMessage,
                   d.created_at AS createdAt, d.updated_at AS updatedAt,
                   COUNT(dc.id) AS chunkCount
            FROM document d
            LEFT JOIN document_chunk dc ON dc.doc_id = d.id
            WHERE d.kb_id = #{kbId}
            GROUP BY d.id
            ORDER BY d.id DESC
            """)
    List<Document> selectByKbId(Long kbId);

    @Update("""
            UPDATE document
            SET content = #{content},
                status = #{status},
                error_message = #{errorMessage},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(Document document);

    @Delete("""
            DELETE FROM document
            WHERE id = #{id}
            """)
    int deleteById(Long id);
}
