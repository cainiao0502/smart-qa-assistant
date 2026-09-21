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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 工具注册表：把「内置工具 / 可执行 Skill / MCP 工具」三类来源统一为可查找、可描述的工具集合。
 *
 * <p><b>跨源重名</b>：三类来源可能声明同名工具（MCP Server 暴露了与内置工具同名的工具，
 * 或两个 Skill 声明了同一个 {@code toolName}）。此时统一按 <b>内置 &gt; Skill &gt; MCP</b> 的优先级归类：
 * 查找（{@link #getRequired(String)}）与声明（{@link #listToolSpecs()}）都只认优先级最高的那一个，
 * 被压住的来源既不执行、也不出现在工具表里——否则会出现「模型看到 N 个同名工具、执行却是另一个来源」
 * 这种不可预期的行为，且部分 provider 会因 function 重名直接拒绝整个请求。
 * 冲突不会被静默吞掉：构造期与首次声明时各 WARN 一次，也可用 {@link #listNameConflicts()} 显式查询。</p>
 *
 * <p><b>同一来源内部重名</b>（两个内置工具同名）直接启动失败：那属于本项目的装配错误，
 * 无法判断该保留哪一个，让它在启动期暴露远比运行期随机胜出安全。</p>
 */
@Component
public class ToolExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutorRegistry.class);

    private final Map<String, ToolExecutor> executors;
    private final McpToolCatalog mcpToolCatalog;
    private final SkillRegistry skillRegistry;
    private final SkillToolExecutorFactory skillToolExecutorFactory;
    /** 已经 WARN 过的重名（key = 工具名@被压住的来源），避免每轮对话都刷同一条日志 */
    private final Set<String> warnedConflicts = ConcurrentHashMap.newKeySet();

    public ToolExecutorRegistry(List<ToolExecutor> executors,
                                McpToolCatalog mcpToolCatalog,
                                SkillRegistry skillRegistry,
                                SkillToolExecutorFactory skillToolExecutorFactory) {
        this.executors = indexBuiltinExecutors(executors);
        this.mcpToolCatalog = mcpToolCatalog;
        this.skillRegistry = skillRegistry;
        this.skillToolExecutorFactory = skillToolExecutorFactory;
        warnShadowedSkillTools();
    }

    /**
     * 按 <b>内置 &gt; Skill &gt; MCP</b> 的优先级查找执行器——与 {@link #listToolSpecs()} 的声明优先级一致，
     * 保证「模型看到的那个工具」就是「实际执行的那个来源」。
     */
    public ToolExecutor getRequired(String toolName) {
        ToolExecutor executor = executors.get(toolName);
        if (executor != null) {
            return executor;
        }

        for (SkillDefinition skill : executableSkills()) {
            if (toolName.equals(skill.getToolName())) {
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
     *
     * <p>跨源重名在这里被<b>收敛成一个声明</b>（内置 &gt; Skill &gt; MCP）：否则同名 function
     * 会被重复写进 {@code tools}，轻则模型分不清该调哪个，重则被 provider 直接判为非法请求。
     */
    public List<ToolSpec> listToolSpecs() {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        Map<String, String> claimedBy = new LinkedHashMap<>();
        for (ToolExecutor executor : executors.values()) {
            claim(specs, claimedBy, executor.getToolName(), executor.getSource(), () -> toToolSpec(executor));
        }
        for (SkillDefinition skill : executableSkills()) {
            ToolExecutor executor = skillToolExecutorFactory.create(skill);
            claim(specs, claimedBy, executor.getToolName(), executor.getSource(), () -> toToolSpec(executor));
        }
        for (McpToolDefinition tool : mcpToolCatalog.listAllTools()) {
            claim(specs, claimedBy, tool.exposedName(), "mcp:" + tool.serverId(), () -> toToolSpec(tool));
        }
        return List.copyOf(specs.values());
    }

    /** 只登记优先级最高的来源；被压住的来源记一条 WARN（每个「工具名@来源」一次），不静默丢弃。 */
    private void claim(Map<String, ToolSpec> specs,
                       Map<String, String> claimedBy,
                       String toolName,
                       String source,
                       Supplier<ToolSpec> specFactory) {
        String winner = claimedBy.get(toolName);
        if (winner != null) {
            warnShadowedOnce(toolName, source, winner);
            return;
        }
        claimedBy.put(toolName, source);
        specs.put(toolName, specFactory.get());
    }

    private void warnShadowedOnce(String toolName, String shadowedSource, String winnerSource) {
        if (warnedConflicts.add(toolName + "@" + shadowedSource)) {
            log.warn("Tool name conflict: '{}' from {} is shadowed by {}; "
                            + "only the highest-priority source is declared and executable "
                            + "(builtin > skill > mcp)",
                    toolName, shadowedSource, winnerSource);
        }
    }

    /** 构造期就能确定的重名（内置工具 vs 可执行 Skill）；MCP 工具要等运行期发现，只能在声明时告警。 */
    private void warnShadowedSkillTools() {
        for (SkillDefinition skill : executableSkills()) {
            ToolExecutor builtin = executors.get(skill.getToolName());
            if (builtin != null) {
                warnShadowedOnce(skill.getToolName(), "skill:" + skill.getName(), builtin.getSource());
            }
        }
    }

    /**
     * 列出跨源重名工具。{@code winnerSource} 是实际生效（既会被声明、也会被执行）的来源，
     * {@code shadowedSources} 是被压住的来源，便于运维排查「为什么我配的 MCP 工具没生效」。
     *
     * <p>注意：MCP 工具来自运行期发现，本方法会触发一次工具目录拉取（与 {@link #listToolSpecs()} 同价）。</p>
     */
    public List<ToolNameConflict> listNameConflicts() {
        Map<String, List<String>> sourcesByName = new LinkedHashMap<>();
        executors.forEach((name, executor) -> addSource(sourcesByName, name, executor.getSource()));
        for (SkillDefinition skill : executableSkills()) {
            addSource(sourcesByName, skill.getToolName(), "skill:" + skill.getName());
        }
        for (McpToolDefinition tool : mcpToolCatalog.listAllTools()) {
            addSource(sourcesByName, tool.exposedName(), "mcp:" + tool.serverId());
        }

        List<ToolNameConflict> conflicts = new ArrayList<>();
        sourcesByName.forEach((name, sources) -> {
            if (sources.size() > 1) {
                conflicts.add(new ToolNameConflict(
                        name,
                        sources.get(0),
                        List.copyOf(sources.subList(1, sources.size()))
                ));
            }
        });
        return List.copyOf(conflicts);
    }

    private static void addSource(Map<String, List<String>> sourcesByName, String toolName, String source) {
        sourcesByName.computeIfAbsent(toolName, key -> new ArrayList<>()).add(source);
    }

    /** 可执行且具备执行器的 Skill（与内置工具同处一张工具表的那部分）。 */
    private List<SkillDefinition> executableSkills() {
        return skillRegistry.listExecutableSkills().stream()
                .filter(skillToolExecutorFactory::supports)
                .toList();
    }

    private static Map<String, ToolExecutor> indexBuiltinExecutors(List<ToolExecutor> executors) {
        Map<String, ToolExecutor> indexed = new LinkedHashMap<>();
        if (executors != null) {
            for (ToolExecutor executor : executors) {
                if (executor == null) {
                    continue;
                }
                ToolExecutor previous = indexed.putIfAbsent(executor.getToolName(), executor);
                if (previous != null) {
                    // 同一来源内部重名属装配错误：保留哪一个都无法自证正确，启动期失败优于运行期随机胜出
                    throw new IllegalStateException("duplicate builtin tool name '%s': %s vs %s".formatted(
                            executor.getToolName(),
                            previous.getClass().getName(),
                            executor.getClass().getName()));
                }
            }
        }
        // 必须保留 LinkedHashMap 的插入顺序：工具声明顺序稳定，prompt 缓存命中率才稳定
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 一次跨源重名：同名工具只保留优先级最高的来源。
     *
     * @param toolName         重名的工具名
     * @param winnerSource     实际生效的来源（会被声明给模型、也会被真正执行）
     * @param shadowedSources  被压住的来源（既不声明也不执行）
     */
    public record ToolNameConflict(String toolName, String winnerSource, List<String> shadowedSources) {
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
