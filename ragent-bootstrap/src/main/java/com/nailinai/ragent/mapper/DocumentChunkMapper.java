package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentChunkMapper {

    @Insert("""
            <script>
            INSERT INTO document_chunk (kb_id, doc_id, chunk_index, chunk_text, token_estimate, paragraph_index, embedding)
            VALUES
            <foreach collection="chunks" item="chunk" separator=",">
                (#{chunk.kbId}, #{chunk.docId}, #{chunk.chunkIndex}, #{chunk.chunkText},
                 #{chunk.tokenEstimate}, #{chunk.paragraphIndex}, CAST(#{chunk.embeddingLiteral} AS vector))
            </foreach>
            </script>
            """)
    int batchInsert(@Param("chunks") List<DocumentChunk> chunks);

    @Delete("DELETE FROM document_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    @Select("""
            SELECT id, kb_id AS kbId, doc_id AS docId, chunk_index AS chunkIndex,
                   chunk_text AS chunkText, token_estimate AS tokenEstimate,
                   paragraph_index AS paragraphIndex, created_at AS createdAt
            FROM document_chunk
            WHERE doc_id = #{docId}
            ORDER BY chunk_index ASC
            """)
    List<DocumentChunk> selectByDocId(@Param("docId") Long docId);

    @Select("""
            <script>
            SELECT
                dc.id,
                dc.kb_id AS kbId,
                dc.doc_id AS docId,
                d.name AS documentName,
                d.file_type AS fileType,
                dc.chunk_index AS chunkIndex,
                dc.chunk_text AS chunkText,
                dc.token_estimate AS tokenEstimate,
                dc.paragraph_index AS paragraphIndex,
                1 - (dc.embedding &lt;=&gt; CAST(#{embeddingLiteral} AS vector)) AS score,
                dc.embedding &lt;=&gt; CAST(#{embeddingLiteral} AS vector) AS distance,
                dc.created_at AS createdAt
            FROM document_chunk dc
            INNER JOIN document d ON d.id = dc.doc_id
            WHERE dc.kb_id = #{kbId}
            <if test="documentIds != null and documentIds.size() > 0">
                AND dc.doc_id IN
                <foreach collection="documentIds" item="docId" open="(" separator="," close=")">
                    #{docId}
                </foreach>
            </if>
            <if test="fileTypes != null and fileTypes.size() > 0">
                AND LOWER(d.file_type) IN
                <foreach collection="fileTypes" item="fileType" open="(" separator="," close=")">
                    LOWER(#{fileType})
                </foreach>
            </if>
            <if test="documentNameKeyword != null and documentNameKeyword != ''">
                AND LOWER(d.name) LIKE CONCAT('%', LOWER(#{documentNameKeyword}), '%')
            </if>
            ORDER BY dc.embedding &lt;=&gt; CAST(#{embeddingLiteral} AS vector)
            LIMIT #{topK}
            </script>
            """)
    List<DocumentChunk> selectTopKByKbId(@Param("kbId") Long kbId,
                                         @Param("embeddingLiteral") String embeddingLiteral,
                                         @Param("documentIds") List<Long> documentIds,
                                         @Param("fileTypes") List<String> fileTypes,
                                         @Param("documentNameKeyword") String documentNameKeyword,
                                         @Param("topK") Integer topK);
}
