package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.infra.chat.ToolSpec;
import com.nailinai.ragent.mcp.McpCallResult;
import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import com.nailinai.ragent.skill.SkillDefinition;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.skill.SkillToolExecutorFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册表：把「内置工具 / 可执行 Skill / MCP 工具」三类来源统一为可查找、可描述的工具集合。
 */
@Component
public class ToolExecutorRegistry {

    private final Map<String, ToolExecutor> executors;
    private final McpToolCatalog mcpToolCatalog;
    private final SkillRegistry skillRegistry;
    private final SkillToolExecutorFactory skillToolExecutorFactory;

    public ToolExecutorRegistry(List<ToolExecutor> executors,
                                McpToolCatalog mcpToolCatalog,
                                SkillRegistry skillRegistry,
                                SkillToolExecutorFactory skillToolExecutorFactory) {
        this.executors = executors.stream()
                .collect(Collectors.toUnmodifiableMap(ToolExecutor::getToolName, item -> item));
        this.mcpToolCatalog = mcpToolCatalog;
        this.skillRegistry = skillRegistry;
        this.skillToolExecutorFactory = skillToolExecutorFactory;
    }

    public ToolExecutor getRequired(String toolName) {
        ToolExecutor executor = executors.get(toolName);
        if (executor != null) {
            return executor;
        }

        for (SkillDefinition skill : skillRegistry.listExecutableSkills()) {
            if (toolName.equals(skill.getToolName()) && skillToolExecutorFactory.supports(skill)) {
                return skillToolExecutorFactory.create(skill);
            }
        }

        McpToolDefinition mcpTool = mcpToolCatalog.findByExposedName(toolName);
        if (mcpTool != null) {
            return new ToolExecutor() {
                @Override
                public String getToolName() {
                    return mcpTool.exposedName();
                }

                @Override
                public String getDisplayName() {
                    return mcpTool.exposedName();
                }

                @Override
                public String getDescription() {
                    return mcpTool.description() == null || mcpTool.description().isBlank()
                            ? "MCP tool from " + mcpTool.serverId()
                            : mcpTool.description();
                }

                @Override
                public String getSource() {
                    return "mcp:" + mcpTool.serverId();
                }

                @Override
                public Map<String, Object> getParametersSchema() {
                    return mcpTool.inputSchema() == null ? Map.of() : mcpTool.inputSchema();
                }

                @Override
                public com.nailinai.ragent.agent.dto.ToolExecutionResult execute(Map<String, Object> arguments,
                                                                                com.nailinai.ragent.agent.dto.ToolContext context) {
                    long startTime = System.currentTimeMillis();
                    McpCallResult result = mcpToolCatalog.callTool(mcpTool, arguments);
                    Map<String, Object> traceArguments = arguments == null
                            ? Map.of()
                            : new LinkedHashMap<>(arguments);
                    String resultPreview = truncate(result.supplementalContext(), 1600);
                    return com.nailinai.ragent.agent.dto.ToolExecutionResult.builder()
                            .trace(ToolCallTraceResponse.builder()
                                    .toolName(mcpTool.exposedName())
                                    .displayName(mcpTool.exposedName())
                                    .source(getSource())
                                    .status(result.isError() ? "FAILED" : "SUCCESS")
                                    .arguments(traceArguments)
                                    .summary(result.summary())
                                    .resultPreview(resultPreview)
                                    .rawResult(result.rawResult())
                                    .durationMs(System.currentTimeMillis() - startTime)
                                    .build())
                            .summary(result.summary())
                            .supplementalContext(result.supplementalContext())
                            .references(List.of())
                            .rawResult(result.rawResult())
                            .observation(result.summary())
                            .build();
                }
            };
        }

        throw new BusinessException(ErrorCode.NOT_FOUND, "tool not found: " + toolName);
    }

    /**
     * 产出面向模型的工具声明列表。
     *
     * <p>这是原生 function calling 的入口：产出的 {@link ToolSpec} 会被序列化为
     * 请求体中的 {@code tools} 字段，取代过去「把工具清单拼成一段文本塞进 prompt」的做法。
     */
    public List<ToolSpec> listToolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (ToolExecutor executor : executors.values()) {
            specs.add(toToolSpec(executor));
        }
        for (SkillDefinition skill : skillRegistry.listExecutableSkills()) {
            if (skillToolExecutorFactory.supports(skill)) {
                specs.add(toToolSpec(skillToolExecutorFactory.create(skill)));
            }
        }
        for (McpToolDefinition tool : mcpToolCatalog.listAllTools()) {
            specs.add(toToolSpec(tool));
        }
        return List.copyOf(specs);
    }

    private ToolSpec toToolSpec(ToolExecutor executor) {
        return ToolSpec.of(executor.getToolName(), executor.getDescription(), executor.getParametersSchema());
    }

    private ToolSpec toToolSpec(McpToolDefinition tool) {
        String description = tool.description() == null || tool.description().isBlank()
                ? "MCP tool from " + tool.serverId()
                : tool.description();
        return ToolSpec.of(tool.exposedName(), description, tool.inputSchema());
    }

    /**
     * 把工具清单渲染为纯文本。
     *
     * @deprecated 已被 {@link #listToolSpecs()} 取代。保留仅为向后兼容；
     * 新的调用方应使用原生工具声明，不要再把工具清单塞进 prompt。
     */
    @Deprecated
    public String describeTools() {
        return java.util.stream.Stream.of(
                        executors.values().stream()
                                .map(tool -> "- %s: %s".formatted(tool.getToolName(), tool.getDescription())),
                        skillRegistry.listExecutableSkills().stream()
                                .filter(skillToolExecutorFactory::supports)
                                .map(skill -> "- %s: %s".formatted(
                                        skill.getToolName(),
                                        skill.getDescription() == null || skill.getDescription().isBlank()
                                                ? "Executable skill from " + skill.getName()
                                                : skill.getDescription()
                                )),
                        mcpToolCatalog.listAllTools().stream()
                                .map(tool -> "- %s: %s".formatted(
                                        tool.exposedName(),
                                        tool.description() == null || tool.description().isBlank()
                                                ? "MCP tool from " + tool.serverId()
                                                : tool.description()
                                ))
                )
                .flatMap(stream -> stream)
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }
}
