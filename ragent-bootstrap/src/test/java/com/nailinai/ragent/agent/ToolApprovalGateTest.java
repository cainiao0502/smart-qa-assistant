package com.nailinai.ragent.agent;

import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import com.nailinai.ragent.mcp.ToolAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具审批闸门的判定与降级行为。
 *
 * <p>重点覆盖三类容易做错的地方：只读工具的外联风险、服务端未声明时的保守处理、
 * 以及「问不到人」时绝不放行。</p>
 */
class ToolApprovalGateTest {

    private McpToolCatalog mcpToolCatalog;
    private ToolApprovalGate gate;

    @BeforeEach
    void setUp() {
        mcpToolCatalog = mock(McpToolCatalog.class);
        gate = new ToolApprovalGate(mcpToolCatalog);
    }

    private McpToolDefinition tool(String name, ToolAnnotations annotations) {
        return new McpToolDefinition("test-server", "test-server", name, name,
                "test tool", Map.of(), annotations);
    }

    private ToolGateContext context(String toolName, ApprovalRequester requester) {
        return new ToolGateContext("run-1", "task_1", toolName, Map.of("q", "probe"),
                0, 0, requester);
    }

    @Test
    @DisplayName("非 MCP 工具不在管辖范围：内置工具语义已知，直接放行")
    void nonMcpToolIsAllowed() {
        when(mcpToolCatalog.findByExposedNameCached("kb_lookup")).thenReturn(null);

        ToolGateDecision decision = gate.beforeToolCall(context("kb_lookup", null));

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("只读且不接触外部世界：放行，且不打扰用户")
    void readOnlyLocalToolIsAllowedWithoutAsking() {
        when(mcpToolCatalog.findByExposedNameCached("tst_read_ledger"))
                .thenReturn(tool("tst_read_ledger", ToolAnnotations.of(true, false, false)));
        AtomicInteger asked = new AtomicInteger();
        ApprovalRequester requester = request -> {
            asked.incrementAndGet();
            return ApprovalOutcome.APPROVED;
        };

        ToolGateDecision decision = gate.beforeToolCall(context("tst_read_ledger", requester));

        assertThat(decision.blocked()).isFalse();
        assertThat(asked.get()).isZero();
    }

    @Test
    @DisplayName("只读但会外联：仍需确认——只读不等于安全")
    void readOnlyButOpenWorldToolRequiresApproval() {
        when(mcpToolCatalog.findByExposedNameCached("tst_fetch_external"))
                .thenReturn(tool("tst_fetch_external", ToolAnnotations.of(true, false, true)));
        AtomicInteger asked = new AtomicInteger();
        ApprovalRequester requester = request -> {
            asked.incrementAndGet();
            assertThat(request.toolName()).isEqualTo("tst_fetch_external");
            assertThat(request.source()).isEqualTo("mcp:test-server");
            assertThat(request.riskSummary()).contains("外");
            return ApprovalOutcome.APPROVED;
        };

        ToolGateDecision decision = gate.beforeToolCall(context("tst_fetch_external", requester));

        assertThat(asked.get()).isEqualTo(1);
        assertThat(decision.blocked()).isFalse();
    }

    @Test
    @DisplayName("破坏性工具被拒：拦截但不封顶任务（授权类而非预算类）")
    void destructiveToolRejectedKeepsTaskOpen() {
        when(mcpToolCatalog.findByExposedNameCached("tst_purge_ledger"))
                .thenReturn(tool("tst_purge_ledger", ToolAnnotations.of(false, true, false)));
        ApprovalRequester requester = request -> ApprovalOutcome.REJECTED;

        ToolGateDecision decision = gate.beforeToolCall(context("tst_purge_ledger", requester));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.category()).isEqualTo(ToolGateDecision.Category.APPROVAL);
        assertThat(decision.reason()).contains("拒绝");
    }

    @Test
    @DisplayName("服务端未声明 annotations：按最保守档位处理（fail-safe）")
    void undeclaredToolIsTreatedAsDangerous() {
        when(mcpToolCatalog.findByExposedNameCached("tst_undeclared_action"))
                .thenReturn(tool("tst_undeclared_action", ToolAnnotations.UNKNOWN));
        AtomicInteger asked = new AtomicInteger();
        ApprovalRequester requester = request -> {
            asked.incrementAndGet();
            return ApprovalOutcome.APPROVED;
        };

        gate.beforeToolCall(context("tst_undeclared_action", requester));

        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有确认通道：拦截并说明原因，绝不默认放行")
    void noChannelBlocksInsteadOfAllowing() {
        when(mcpToolCatalog.findByExposedNameCached("tst_purge_ledger"))
                .thenReturn(tool("tst_purge_ledger", ToolAnnotations.of(false, true, false)));

        ToolGateDecision decision = gate.beforeToolCall(context("tst_purge_ledger", null));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains("没有可用的确认通道");
    }

    @Test
    @DisplayName("等待超时/连接中断：按未授权处理，与「用户明确拒绝」区分措辞")
    void unavailableChannelIsTreatedAsNotApproved() {
        when(mcpToolCatalog.findByExposedNameCached("tst_purge_ledger"))
                .thenReturn(tool("tst_purge_ledger", ToolAnnotations.of(false, true, false)));
        ApprovalRequester requester = request -> ApprovalOutcome.UNAVAILABLE;

        ToolGateDecision decision = gate.beforeToolCall(context("tst_purge_ledger", requester));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains("未得到响应");
        assertThat(decision.reason()).doesNotContain("用户拒绝");
    }

    @Test
    @DisplayName("受信清单中的工具跳过确认（管理员显式授权）")
    void trustedToolSkipsApproval() {
        when(mcpToolCatalog.findByExposedNameCached("tst_purge_ledger"))
                .thenReturn(tool("tst_purge_ledger", ToolAnnotations.of(false, true, false)));
        gate.setTrustedTools("tst_purge_ledger, other_tool");
        AtomicInteger asked = new AtomicInteger();
        ApprovalRequester requester = request -> {
            asked.incrementAndGet();
            return ApprovalOutcome.APPROVED;
        };

        ToolGateDecision decision = gate.beforeToolCall(context("tst_purge_ledger", requester));

        assertThat(decision.blocked()).isFalse();
        assertThat(asked.get()).isZero();
    }

    @Test
    @DisplayName("开关关闭时整条策略不介入")
    void disabledGateAllowsEverything() {
        when(mcpToolCatalog.findByExposedNameCached("tst_purge_ledger"))
                .thenReturn(tool("tst_purge_ledger", ToolAnnotations.of(false, true, false)));
        gate.setEnabled(false);

        ToolGateDecision decision = gate.beforeToolCall(context("tst_purge_ledger", null));

        assertThat(decision.blocked()).isFalse();
    }
}
