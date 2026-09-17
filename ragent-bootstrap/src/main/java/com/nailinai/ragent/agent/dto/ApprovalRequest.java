package com.nailinai.ragent.agent.dto;

import java.util.Map;

/**
 * 一次待审批的工具调用（推给人工确认界面的内容）。
 *
 * <p>不放 provider 等无关字段：审批界面需要让用户判断的是
 * <b>「Agent 想做什么、对谁做、有什么风险」</b>，因此参数与风险说明必须显式给出，
 * 而不是只丢一个工具名让用户猜。</p>
 *
 * <p>审批标识（approvalId）刻意不在这里——它是「通道内部的关联键」而非「调用内容」，
 * 由持有待决表的通道实现生成，避免策略层需要关心关联与清理。</p>
 *
 * @param runId        所属运行
 * @param toolName     工具名（暴露名）
 * @param displayName  工具展示名
 * @param source       工具来源（{@code mcp:<serverId>} / {@code builtin} / {@code skill}）
 * @param arguments    调用参数（原样给出，人工判断的主要依据）
 * @param riskSummary  风险说明（由工具声明的 annotations 推导）
 */
public record ApprovalRequest(
        String runId,
        String toolName,
        String displayName,
        String source,
        Map<String, Object> arguments,
        String riskSummary
) {

    public ApprovalRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
