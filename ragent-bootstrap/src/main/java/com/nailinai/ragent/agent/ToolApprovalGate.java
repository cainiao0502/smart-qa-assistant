package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.ApprovalRequest;
import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具审批闸门：对有副作用或会外联的工具，在执行前取得人工确认。
 *
 * <p><b>存在意义。</b> MCP 工具被注册进 Agent 工具表后，就成了一条「用户提问 →
 * 模型自主决定 → 服务端执行」的通路。管理员配置 MCP Server 只是开放了能力，
 * 并不等于授权每一次调用：一旦某个 MCP Server 带有写文件、改数据或抓取任意 URL
 * 的工具，普通用户就能通过一句话间接驱动它。本项目此前没有任何审查环节，
 * 模型决定了就直接执行。</p>
 *
 * <p><b>判定依据用协议自带的元数据，不自己维护风险表。</b> MCP 规范的
 * {@code tools/list} 允许工具提供方声明 {@code readOnlyHint} / {@code destructiveHint} /
 * {@code openWorldHint}，这比客户端凭工具名猜测可靠得多，也不会随工具增减而失同步。
 * 客户端此前把整个 annotations 节点丢掉了，本策略的前提正是把它补回来。</p>
 *
 * <p><b>三条判定原则</b>：</p>
 * <ol>
 *   <li><b>fail-safe</b>——服务端没声明不等于安全。未声明时按
 *       {@code readOnlyHint=false} 处理，一律需要确认；</li>
 *   <li><b>只读 ≠ 安全</b>——抓取任意 URL 的工具也是只读的，但它能探测内网、
 *       把上下文内容带到第三方，因此 {@code openWorldHint} 是独立判据；</li>
 *   <li><b>问不到人就不放行</b>——没有可用审批通道时拦截并说明原因，
 *       绝不把「系统问不到」当成「用户同意」。</li>
 * </ol>
 *
 * <p><b>职责边界</b>：本策略只管辖 MCP 来源的工具。内置工具是自研的、语义已知
 * （检索 / 目录 / 文档详情，全部只读），不在管辖范围；判定依据来自来源而非硬编码名单。</p>
 */
@Component
public class ToolApprovalGate implements AgentTurnHook {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGate.class);

    private final McpToolCatalog mcpToolCatalog;

    /** 管理员显式列入受信清单的工具（暴露名），命中则跳过审批 */
    private final Set<String> trustedTools = new LinkedHashSet<>();

    private boolean enabled = true;

    public ToolApprovalGate(McpToolCatalog mcpToolCatalog) {
        this.mcpToolCatalog = mcpToolCatalog;
    }

    @Value("${app.agent.tool-approval.enabled:true}")
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 逗号分隔的受信工具清单：仅用于管理员确认过、且不便逐次确认的场景。 */
    @Value("${app.agent.tool-approval.trusted-tools:}")
    public void setTrustedTools(String trustedTools) {
        this.trustedTools.clear();
        if (StringUtils.hasText(trustedTools)) {
            this.trustedTools.addAll(Arrays.stream(trustedTools.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
    }

    @Override
    public ToolGateDecision beforeToolCall(ToolGateContext context) {
        if (!enabled) {
            return ToolGateDecision.allow();
        }

        McpToolDefinition tool = mcpToolCatalog.findByExposedNameCached(context.toolName());
        if (tool == null) {
            // 非 MCP 工具：内置工具语义已知、技能工具有各自的管理路径，均不在本策略管辖内
            return ToolGateDecision.allow();
        }
        if (trustedTools.contains(context.toolName())) {
            return ToolGateDecision.allow();
        }
        if (tool.annotations().safeToAutoRun()) {
            return ToolGateDecision.allow();
        }

        String risk = tool.annotations().describeRisk();

        if (!context.hasApprovalChannel()) {
            log.info("Tool approval required but no channel available: runId={}, tool={}",
                    context.runId(), context.toolName());
            return ToolGateDecision.block(
                    "工具 %s 需要人工授权（%s），但当前请求没有可用的确认通道，因此未执行。"
                            .formatted(context.toolName(), risk)
                            + "请改用无需授权的方式获取信息，或明确告知用户该操作需要人工确认后再执行。",
                    ToolGateDecision.Category.APPROVAL);
        }

        ApprovalRequest request = new ApprovalRequest(
                context.runId(),
                context.toolName(),
                tool.exposedName(),
                "mcp:" + tool.serverId(),
                context.arguments(),
                risk
        );

        ApprovalOutcome outcome = context.approvalRequester().request(request);

        if (outcome == ApprovalOutcome.APPROVED) {
            log.info("Tool call approved by user: runId={}, tool={}", context.runId(), context.toolName());
            return ToolGateDecision.allow();
        }

        if (outcome == ApprovalOutcome.REJECTED) {
            log.info("Tool call rejected by user: runId={}, tool={}", context.runId(), context.toolName());
            return ToolGateDecision.block(
                    "用户拒绝了对工具 %s 的授权，本次调用未执行。".formatted(context.toolName())
                            + "请改用其他方式完成该任务，或向用户说明这一步没有完成。",
                    ToolGateDecision.Category.APPROVAL);
        }

        // UNAVAILABLE：等待超时或连接中断——不能因为问不到人就默认执行
        log.info("Tool approval channel unavailable: runId={}, tool={}", context.runId(), context.toolName());
        return ToolGateDecision.block(
                "工具 %s 需要人工授权（%s），但确认请求未得到响应（超时或连接中断），因此未执行。"
                        .formatted(context.toolName(), risk)
                        + "请改用其他方式，或提示用户该操作需要确认。",
                ToolGateDecision.Category.APPROVAL);
    }
}
