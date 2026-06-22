package com.nailinai.ragent.mapper;

import com.nailinai.ragent.entity.AgentStepEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentStepMapper {

    @Insert("""
            INSERT INTO agent_step (run_id, step_index, step_type, tool_name, arguments_json, reason, observation_summary, status, duration_ms)
            VALUES (#{runId}, #{stepIndex}, #{stepType}, #{toolName}, CAST(#{argumentsJson} AS jsonb), #{reason}, #{observationSummary}, #{status}, #{durationMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentStepEntity agentStep);

    @Select("""
            SELECT id,
                   run_id AS runId,
                   step_index AS stepIndex,
                   step_type AS stepType,
                   tool_name AS toolName,
                   arguments_json::text AS argumentsJson,
                   reason,
                   observation_summary AS observationSummary,
                   status,
                   duration_ms AS durationMs,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM agent_step
            WHERE run_id = #{runId}
            ORDER BY step_index ASC, id ASC
            """)
    List<AgentStepEntity> selectByRunId(String runId);
}
