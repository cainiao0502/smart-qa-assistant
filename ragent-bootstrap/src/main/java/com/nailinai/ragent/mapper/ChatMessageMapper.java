package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatMessageMapper {

    @Insert("""
            INSERT INTO chat_message (session_id, kb_id, run_id, role, content, references_json, tool_calls_json, owner_user_id)
            VALUES (#{sessionId}, #{kbId}, #{runId}, #{role}, #{content}, CAST(#{referencesJson} AS jsonb), CAST(#{toolCallsJson} AS jsonb), #{ownerUserId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatMessage chatMessage);

    @Select("""
            SELECT id,
                   session_id AS sessionId,
                   kb_id AS kbId,
                   run_id AS runId,
                   role,
                   content,
                   references_json::text AS referencesJson,
                   tool_calls_json::text AS toolCallsJson,
                   owner_user_id AS ownerUserId,
                   created_at AS createdAt
            FROM chat_message
            WHERE session_id = #{sessionId}
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMessage> selectBySessionId(String sessionId);

    @Select("""
            SELECT id,
                   session_id AS sessionId,
                   kb_id AS kbId,
                   run_id AS runId,
                   role,
                   content,
                   references_json::text AS referencesJson,
                   tool_calls_json::text AS toolCallsJson,
                   owner_user_id AS ownerUserId,
                   created_at AS createdAt
            FROM chat_message
            WHERE session_id = #{sessionId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectRecentBySessionId(String sessionId, int limit);

    @Delete("""
            DELETE FROM chat_message
            WHERE kb_id = #{kbId}
            """)
    int deleteByKbId(Long kbId);

    @Select("""
            SELECT session_id AS "sessionId",
                   COUNT(*) AS "messageCount",
                   MIN(content) FILTER (WHERE role = 'USER') AS title,
                   MAX(kb_id) AS "kbId",
                   MAX(created_at) AS "lastActivityAt"
            FROM chat_message
            WHERE owner_user_id = #{ownerUserId}
            GROUP BY session_id
            ORDER BY MAX(created_at) DESC
            """)
    List<Map<String, Object>> selectSessionSummaries(Long ownerUserId);

    @Delete("""
            DELETE FROM chat_message
            WHERE session_id = #{sessionId}
            """)
    int deleteBySessionId(String sessionId);
}
