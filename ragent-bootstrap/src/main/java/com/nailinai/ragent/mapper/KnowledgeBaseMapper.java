package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {

    @Insert("""
            INSERT INTO knowledge_base (name, description)
            VALUES (#{name}, #{description})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBase knowledgeBase);

    @Select("""
            SELECT id, name, description, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            ORDER BY id DESC
            """)
    List<KnowledgeBase> selectAll();

    @Select("""
            SELECT id, name, description, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            WHERE id = #{id}
            """)
    KnowledgeBase selectById(Long id);

    @Delete("""
            DELETE FROM knowledge_base
            WHERE id = #{id}
            """)
    int deleteById(Long id);
}
