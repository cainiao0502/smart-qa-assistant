package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRun;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.entity.AgentRunEntity;
import com.nailinai.ragent.mapper.AgentRunMapper;
import com.nailinai.ragent.mapper.AgentStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRunStore 归属字段（owner_user_id）读写与 run 详情组装的单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRunStoreTest {

    @Mock
    private AgentRunMapper agentRunMapper;
    @Mock
    private AgentStepMapper agentStepMapper;
    @Mock
    private AgentStepHistoryService agentStepHistoryService;

    private AgentRunStore store;

    @BeforeEach
    void setUp() {
        store = new AgentRunStore(agentRunMapper, agentStepMapper, agentStepHistoryService);
    }

    @Test
    @DisplayName("createRun 把归属用户写入实体")
    void createRun_shouldPersistOwnerUserId() {
        AgentRun run = AgentRun.builder()
                .runId("run_1")
                .sessionId("sess-1")
                .kbId(1L)
                .userGoal("问题")
                .status("RUNNING")
                .ownerUserId(42L)
                .build();

        store.createRun(run);

        ArgumentCaptor<AgentRunEntity> captor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(agentRunMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("getRunDetail 把 owner_user_id 透出到详情响应")
    void getRunDetail_shouldExposeOwnerUserId() {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId("run_1");
        entity.setSessionId("sess-1");
        entity.setOwnerUserId(42L);
        entity.setStatus("SUCCESS");
        when(agentRunMapper.selectByRunId("run_1")).thenReturn(entity);
        when(agentStepHistoryService.getSteps("run_1")).thenReturn(java.util.List.of());

        AgentRunDetailResponse detail = store.getRunDetail("run_1");

        assertThat(detail.getOwnerUserId()).isEqualTo(42L);
        assertThat(detail.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("历史 run 的 owner 为 NULL：详情照常透出，由调用方回退会话校验")
    void legacyRunWithoutOwner_shouldStillBeReadable() {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId("run_legacy");
        entity.setSessionId("sess-legacy");
        entity.setOwnerUserId(null);
        when(agentRunMapper.selectByRunId("run_legacy")).thenReturn(entity);
        when(agentStepHistoryService.getSteps("run_legacy")).thenReturn(java.util.List.of());

        AgentRunDetailResponse detail = store.getRunDetail("run_legacy");

        assertThat(detail.getOwnerUserId()).isNull();
        assertThat(detail.getSessionId()).isEqualTo("sess-legacy");
    }
}
