package com.nailinai.ragent.user.mapper;

import com.nailinai.ragent.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO t_user (username, password_hash, role)
            VALUES (#{username}, #{passwordHash}, #{role})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, role,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM t_user
            WHERE username = #{username}
            """)
    User selectByUsername(String username);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, role,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM t_user
            WHERE id = #{id}
            """)
    User selectById(Long id);

    @Select("""
            SELECT COUNT(*) FROM t_user
            """)
    long count();
}