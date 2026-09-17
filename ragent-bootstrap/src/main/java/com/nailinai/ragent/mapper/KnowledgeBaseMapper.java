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
            INSERT INTO knowledge_base (name, description, owner_user_id)
            VALUES (#{name}, #{description}, #{ownerUserId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBase knowledgeBase);

    @Select("""
            SELECT id, name, description, owner_user_id AS ownerUserId,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            WHERE owner_user_id = #{ownerUserId}
            ORDER BY id DESC
            """)
    List<KnowledgeBase> selectByOwner(Long ownerUserId);

    @Select("""
            SELECT id, name, description, owner_user_id AS ownerUserId,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            WHERE id = #{id}
            """)
    KnowledgeBase selectById(Long id);

    @Select("""
            SELECT id, name, description, owner_user_id AS ownerUserId,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            WHERE id = #{id} AND owner_user_id = #{ownerUserId}
            """)
    KnowledgeBase selectByIdAndOwner(Long id, Long ownerUserId);

    @Delete("""
            DELETE FROM knowledge_base
            WHERE id = #{id}
            """)
    int deleteById(Long id);
}
