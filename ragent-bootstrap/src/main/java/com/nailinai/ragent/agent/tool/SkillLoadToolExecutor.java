package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.skill.SkillDefinition;
import com.nailinai.ragent.skill.SkillRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能加载工具：Agent Skills 渐进式披露的第二层入口。
 *
 * <p>第一层是技能目录（名称 + 描述，随工具表/提示词常驻）；第二层是 SKILL.md 正文——
 * <b>只有模型调用本工具时才进入上下文</b>，取代过去「用户勾选后全文注入 planner 与
 * 最终回答各一遍」的做法。非可执行技能（纯知识型）只存在这一条生效通路，
 * 与可执行技能的工具化通路（{@code executorType} 声明）互补。</p>
 */
@Component
public class SkillLoadToolExecutor implements ToolExecutor {

    public static final String TOOL_NAME = "load_skill";

    private static final int PREVIEW_CHARS = 1200;

    private final SkillRegistry skillRegistry;

    public SkillLoadToolExecutor(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getDisplayName() {
        return "加载技能";
    }

    @Override
    public String getDescription() {
        String catalog = skillRegistry.renderFullSkillCatalog();
        String base = "加载指定技能的完整操作指引（SKILL.md 正文）到本轮上下文。"
                + "当任务属于某个技能的领域、且目录里只有一句话概述不够用时调用它。";
        return StringUtils.hasText(catalog)
                ? base + " 可用技能：\n" + catalog
                : base + " 当前没有已安装的技能。";
    }

    @Override
    public String getSource() {
        return "builtin";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "技能名（即技能目录名），必须来自可用技能列表"
        ));
        return ToolExecutor.objectSchema(properties, List.of("name"));
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
        String name = arguments == null || arguments.get("name") == null
                ? null
                : String.valueOf(arguments.get("name")).trim();
        // getSkill 抛 NOT_FOUND 是诚实失败：走统一的失败分类与回灌，不让模型拿到空正文还以为加载成功
        SkillDefinition skill = skillRegistry.getSkill(name);

        String body = skill.getContent() == null ? "" : skill.getContent().trim();
        String summary = "Loaded skill %s%s".formatted(
                skill.getName(),
                StringUtils.hasText(body) ? "（%d 字）".formatted(body.length()) : "（正文为空）");

        return ToolExecutionResult.builder()
                .trace(ToolCallTraceResponse.builder()
                        .toolName(TOOL_NAME)
                        .displayName(getDisplayName())
                        .source(getSource())
                        .status("SUCCESS")
                        .arguments(Map.of("name", skill.getName()))
                        .summary(summary)
                        .resultPreview(truncate(body))
                        .durationMs(0L)
                        .build())
                .summary(summary)
                .supplementalContext(body)
                .references(List.of())
                .rawResult(Map.of("skillName", skill.getName(), "contentLength", body.length()))
                .observation(body)
                .build();
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return "(empty)";
        }
        return value.length() <= PREVIEW_CHARS ? value : value.substring(0, PREVIEW_CHARS) + "...";
    }
}
