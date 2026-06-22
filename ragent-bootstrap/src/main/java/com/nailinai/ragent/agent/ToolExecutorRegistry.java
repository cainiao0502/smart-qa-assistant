package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.mcp.McpCallResult;
import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import com.nailinai.ragent.skill.SkillDefinition;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.skill.SkillToolExecutorFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
                public String getSource() {
                    return "mcp:" + mcpTool.serverId();
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

        throw new BusinessException(ErrorCode.BAD_REQUEST, "tool not found: " + toolName);
    }

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
