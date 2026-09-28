package com.nailinai.ragent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估门禁退出路径的单元测试（E1）。
 *
 * <p>锁死的性质：CI 门禁模式（exit-after-run=true）下，<b>任何一种「评估没跑成」
 * 都必须以非零退出亮红灯</b>——连续失败 tripwire 中止 → exit(1)；门禁阈值失败 →
 * 异常穿透使启动失败；只有评估真正跑完才 exit(0)。否则门禁会把「评估没跑成」
 * 包装成绿灯（09-16 教训「403 被读成命中 0 条」的进程级重演）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagEvaluationRunnerGateTest {

    @Mock
    private RagEvaluator ragEvaluator;
    @Mock
    private RagSemanticEvaluator semanticEvaluator;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("评估未启用 + CI 门禁模式：以非零退出亮红灯（门禁没跑就等于没把关）")
    void notEnabled_inGateMode_shouldExitNonZero() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(false, 0.0, true));
        doNothing().when(runner).exitGate(anyInt());

        runner.run(null);

        verify(runner).exitGate(1);
    }

    @Test
    @DisplayName("评估未启用 + 本地模式：不退出、不干扰正常启动（开关默认关闭，不该有副作用）")
    void notEnabled_localMode_shouldNotExit() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(false, 0.0, false));
        doNothing().when(runner).exitGate(anyInt());

        runner.run(null);

        verify(runner, never()).exitGate(anyInt());
    }

    @Test
    @DisplayName("tripwire 中止 + CI 门禁模式：以非零退出亮红灯，而不是绿灯")
    void tripwireAbort_inGateMode_shouldExitNonZero() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(0.0, true));
        doNothing().when(runner).exitGate(anyInt());
        when(ragEvaluator.evaluate(any(), anyBoolean(), anyBoolean()))
                .thenThrow(new EvaluationAbortedException("连续 3 条查询失败"));

        runner.run(null);

        verify(runner).exitGate(1);
    }

    @Test
    @DisplayName("评估正常完成 + CI 门禁模式：以零退出")
    void normalCompletion_inGateMode_shouldExitZero() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(0.0, true));
        doNothing().when(runner).exitGate(anyInt());
        when(ragEvaluator.evaluate(any(), anyBoolean(), anyBoolean())).thenReturn(passReport());
        when(ragEvaluator.evaluate(any(), anyBoolean(), anyBoolean(), anyDouble())).thenReturn(passReport());

        runner.run(null);

        verify(runner).exitGate(0);
    }

    @Test
    @DisplayName("min-recall 门禁失败：异常穿透使启动失败，不走到退出出口")
    void gateThresholdFailure_shouldPropagateWithoutCleanExit() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(0.5, true));
        doNothing().when(runner).exitGate(anyInt());
        when(ragEvaluator.evaluate(any(), anyBoolean(), anyBoolean())).thenReturn(failReport());

        // 门禁阈值失败走 IllegalStateException：ApplicationRunner 异常 = 启动失败 = 非零退出，
        // 不能被 exitGate(0) 抢先「洗干净」
        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("评估门禁失败");
        verify(runner, never()).exitGate(anyInt());
    }

    @Test
    @DisplayName("评估集与库脱节：以非零退出亮红灯，不能静默退出被 CI 读成绿灯")
    void evaluationSetOutOfSync_shouldExitNonZero() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(0.0, true));
        doNothing().when(runner).exitGate(anyInt());
        // 库里没有任何期望文档 → evaluationSetInSyncWithDb 判定脱节
        when(jdbcTemplate.queryForList(eq("SELECT name FROM document WHERE kb_id = ?"), eq(String.class), eq(1L)))
                .thenReturn(List.of("other.txt"));

        runner.run(null);

        verify(runner).exitGate(1);
    }

    @Test
    @DisplayName("owner 解析失败（kbId 不存在或缺 owner_user_id）：以非零退出亮红灯")
    void ownerUnresolvable_shouldExitNonZero() throws Exception {
        RagEvaluationRunner runner = spy(newRunner(0.0, true));
        doNothing().when(runner).exitGate(anyInt());
        // resolveKbOwnerUserId 捕获 EmptyResultDataAccessException 后返回 null
        when(jdbcTemplate.queryForObject(eq("SELECT owner_user_id FROM knowledge_base WHERE id = ?"),
                eq(Long.class), eq(1L))).thenThrow(new EmptyResultDataAccessException(1));

        runner.run(null);

        verify(runner).exitGate(1);
    }

    /** enabled=true / skip-rewrite=true / exit-after-run 可配 的最小 runner，评估集指向临时文件 */
    private RagEvaluationRunner newRunner(double minRecall, boolean exitAfterRun) throws Exception {
        return newRunner(true, minRecall, exitAfterRun);
    }

    private RagEvaluationRunner newRunner(boolean enabled, double minRecall, boolean exitAfterRun) throws Exception {
        Path setFile = tempDir.resolve("eval-set.json");
        java.nio.file.Files.writeString(setFile,
                "{\"kbId\":1,\"topK\":4,\"queries\":[{\"question\":\"q1\",\"expectedDocuments\":[\"aaa.txt\"]}]}");
        // 评估集同步检查：库里有期望文档；owner 解析：kbId=1 → owner 1
        when(jdbcTemplate.queryForList(eq("SELECT name FROM document WHERE kb_id = ?"), eq(String.class), eq(1L)))
                .thenReturn(List.of("aaa.txt"));
        when(jdbcTemplate.queryForObject(eq("SELECT owner_user_id FROM knowledge_base WHERE id = ?"),
                eq(Long.class), eq(1L))).thenReturn(1L);

        return new RagEvaluationRunner(ragEvaluator, semanticEvaluator, new ObjectMapper(), jdbcTemplate,
                enabled, false, setFile.toString(), 0.4, true, minRecall, exitAfterRun);
    }

    private EvaluationReport passReport() {
        RagEvaluationQuery query = new RagEvaluationQuery("q1", List.of("aaa.txt"), null);
        return EvaluationReport.aggregate(false, true, List.of(
                new QueryEvaluation(query, List.of("aaa.txt"), List.of(new DocumentChunk()),
                        EvaluationMetrics.of(1, 1, 1))));
    }

    private EvaluationReport failReport() {
        RagEvaluationQuery query = new RagEvaluationQuery("q1", List.of("aaa.txt"), null);
        return EvaluationReport.aggregate(false, true, List.of(
                new QueryEvaluation(query, List.of(), List.of(), EvaluationMetrics.of(0, 1, 0))));
    }
}
