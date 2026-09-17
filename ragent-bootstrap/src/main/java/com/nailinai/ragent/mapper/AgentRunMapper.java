package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface AgentRunMapper {

    @Insert("""
            INSERT INTO agent_run (run_id, session_id, kb_id, user_goal, status, final_answer)
            VALUES (#{runId}, #{sessionId}, #{kbId}, #{userGoal}, #{status}, #{finalAnswer})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentRunEntity agentRun);

    @Select("""
            SELECT id,
                   run_id AS runId,
                   session_id AS sessionId,
                   kb_id AS kbId,
                   user_goal AS userGoal,
                   status,
                   final_answer AS finalAnswer,
                   duration_ms AS durationMs,
                   llm_calls AS llmCalls,
                   input_tokens AS inputTokens,
                   output_tokens AS outputTokens,
                   cached_tokens AS cachedTokens,
                   reasoning_tokens AS reasoningTokens,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM agent_run
            WHERE run_id = #{runId}
            """)
    AgentRunEntity selectByRunId(String runId);

    @Update("""
            UPDATE agent_run
            SET status = #{status},
                final_answer = #{finalAnswer},
                duration_ms = #{durationMs},
                llm_calls = #{llmCalls},
                input_tokens = #{inputTokens},
                output_tokens = #{outputTokens},
                cached_tokens = #{cachedTokens},
                reasoning_tokens = #{reasoningTokens},
                updated_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
            """)
    int updateResult(AgentRunEntity agentRun);

    @Delete("""
            DELETE FROM agent_run
            WHERE kb_id = #{kbId}
            """)
    int deleteByKbId(Long kbId);
}
