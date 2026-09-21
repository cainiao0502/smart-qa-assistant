package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ToolSpec;
import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import com.nailinai.ragent.skill.SkillDefinition;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.skill.SkillToolExecutorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表的跨源重名处理。
 *
 * <p>内置工具、可执行 Skill、MCP 工具三张表各自独立，同名不是「不可能事件」：MCP Server
 * 完全可以暴露一个叫 {@code kb_lookup} 的工具，两个 Skill 也可以声明同一个 {@code toolName}。
 * 过去这种情况会静默按「内置优先」执行，而声明列表里却把同名 function 重复下发两遍——</p>
 *
 * <ul>
 *   <li>模型这一侧：看到两个同名工具，无法判断该调哪个；</li>
 *   <li>provider 这一侧：部分 OpenAI 兼容实现会因 function 重名直接判请求非法；</li>
 *   <li>运维这一侧：自己配的 MCP 工具没生效，日志里却什么都没有。</li>
 * </ul>
 *
 * <p>因此这里同时盯住三件事：声明只保留优先级最高的来源、查找与声明的优先级一致、
 * 冲突有可查询的出口（{@link ToolExecutorRegistry#listNameConflicts()}）。</p>
 */
class ToolExecutorRegistryTest {

    private McpToolCatalog mcpToolCatalog;
    private SkillRegistry skillRegistry;
    private SkillToolExecutorFactory skillToolExecutorFactory;

    @BeforeEach
    void setUp() {
        mcpToolCatalog = mock(McpToolCatalog.class);
        skillRegistry = mock(SkillRegistry.class);
        skillToolExecutorFactory = new SkillToolExecutorFactory(mock(ChatClient.class));
        when(skillRegistry.listExecutableSkills()).thenReturn(List.of());
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of());
    }

    private ToolExecutorRegistry registry(ToolExecutor... builtinExecutors) {
        return new ToolExecutorRegistry(
                List.of(builtinExecutors), mcpToolCatalog, skillRegistry, skillToolExecutorFactory);
    }

    @Test
    @DisplayName("工具声明保持注册顺序：顺序稳定，prompt 缓存命中率才稳定")
    void listToolSpecsShouldKeepRegistrationOrder() {
        ToolExecutorRegistry registry = registry(
                builtin("kb_lookup", "内置检索"),
                builtin("kb_catalog", "内置目录"));

        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name)
                .containsExactly("kb_lookup", "kb_catalog");
    }

    @Test
    @DisplayName("两个内置工具同名属装配错误：启动期直接失败，不在运行期随机胜出")
    void duplicateBuiltinToolNamesShouldFailFast() {
        assertThatThrownBy(() -> registry(
                builtin("kb_lookup", "A"),
                builtin("kb_lookup", "B")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate builtin tool name 'kb_lookup'");
    }

    @Test
    @DisplayName("Skill 与内置工具同名：只声明一次，内置胜出（与 getRequired 的查找优先级一致）")
    void skillShadowedByBuiltinTool() {
        when(skillRegistry.listExecutableSkills()).thenReturn(List.of(
                skill("codeagent", "kb_lookup", "技能版检索")));

        ToolExecutorRegistry registry = registry(builtin("kb_lookup", "内置检索"));

        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name).containsExactly("kb_lookup");
        assertThat(registry.listToolSpecs()).extracting(ToolSpec::description).containsExactly("内置检索");
        assertThat(registry.getRequired("kb_lookup").getSource()).isEqualTo("builtin");

        assertThat(registry.listNameConflicts()).containsExactly(
                new ToolExecutorRegistry.ToolNameConflict(
                        "kb_lookup", "builtin", List.of("skill:codeagent")));
    }

    @Test
    @DisplayName("Skill 与 MCP 工具同名：技能胜出，且执行走技能执行器")
    void skillShadowsMcpToolWithSameName() {
        when(skillRegistry.listExecutableSkills()).thenReturn(List.of(
                skill("web-helper", "web_search", "技能版搜索")));
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of(
                mcpTool("web-server", "web_search")));

        ToolExecutorRegistry registry = registry();

        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name).containsExactly("web_search");
        assertThat(registry.listToolSpecs()).extracting(ToolSpec::description).containsExactly("技能版搜索");
        assertThat(registry.getRequired("web_search").getSource()).isEqualTo("skill:web-helper");

        assertThat(registry.listNameConflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.toolName()).isEqualTo("web_search");
            assertThat(conflict.winnerSource()).isEqualTo("skill:web-helper");
            assertThat(conflict.shadowedSources()).containsExactly("mcp:web-server");
        });
    }

    @Test
    @DisplayName("两个 MCP Server 暴露同名工具：去重为一个声明，避免同名 function 重复下发")
    void duplicateMcpToolNamesShouldBeDeclaredOnce() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of(
                mcpTool("server-a", "web_search"),
                mcpTool("server-b", "web_search")));

        ToolExecutorRegistry registry = registry();

        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name).containsExactly("web_search");
        assertThat(registry.listNameConflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.winnerSource()).isEqualTo("mcp:server-a");
            assertThat(conflict.shadowedSources()).containsExactly("mcp:server-b");
        });
    }

    @Test
    @DisplayName("没有重名时不报冲突，声明数量等于各来源之和")
    void noConflictWhenNamesAreUnique() {
        when(mcpToolCatalog.listAllTools()).thenReturn(List.of(
                mcpTool("web-server", "web_search")));

        ToolExecutorRegistry registry = registry(builtin("kb_lookup", "内置检索"));

        assertThat(registry.listNameConflicts()).isEmpty();
        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name)
                .containsExactly("kb_lookup", "web_search");
    }

    @Test
    @DisplayName("重复调用声明接口不会累积重复项（每轮对话都会调一次）")
    void listToolSpecsIsIdempotent() {
        when(skillRegistry.listExecutableSkills()).thenReturn(List.of(
                skill("codeagent", "kb_lookup", "技能版检索")));

        ToolExecutorRegistry registry = registry(builtin("kb_lookup", "内置检索"));

        assertThat(registry.listToolSpecs()).hasSize(1);
        assertThat(registry.listToolSpecs()).extracting(ToolSpec::name).containsExactly("kb_lookup");
    }

    private static ToolExecutor builtin(String toolName, String description) {
        return new ToolExecutor() {
            @Override
            public String getToolName() {
                return toolName;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
                return ToolExecutionResult.builder().summary(toolName).build();
            }
        };
    }

    private static SkillDefinition skill(String name, String toolName, String description) {
        return SkillDefinition.builder()
                .name(name)
                .title(name)
                .description(description)
                .toolName(toolName)
                .executorType("llm_transform")
                .executable(true)
                .build();
    }

    private static McpToolDefinition mcpTool(String serverId, String exposedName) {
        return new McpToolDefinition(
                serverId, serverId, exposedName, exposedName, "MCP tool from " + serverId, Map.of());
    }
}
