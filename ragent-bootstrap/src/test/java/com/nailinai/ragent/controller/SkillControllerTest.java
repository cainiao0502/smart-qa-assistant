package com.nailinai.ragent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.dto.response.SkillDefinitionResponse;
import com.nailinai.ragent.dto.response.SkillDetailResponse;
import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.skill.SkillToolExecutorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 技能接口的信息面测试。
 *
 * <p>对话页勾选 skill 的前提是普通用户能拿到技能目录，而目录接口对普通用户开放，
 * 因此这里盯住两件事：<b>目录能拿到</b>、<b>正文不会随目录下发</b>（正文只应由模型
 * 调 {@code load_skill} 时进入上下文）。管理端的详情接口则保持返回正文。</p>
 */
class SkillControllerTest {

    private static final String SKILL_BODY = "先理解现有结构，再决定改动位置。";

    private SkillController controller(Path root) throws IOException {
        writeSkill(root, "codeagent", """
                ---
                title: Code Agent
                description: 把检索到的代码上下文转成实现建议
                toolName: codeagent_transform
                executorType: llm_transform
                ---

                # Code Agent

                %s
                """.formatted(SKILL_BODY));
        writeSkill(root, "readme", """
                ---
                title: 资料
                description: 只是一份说明
                ---

                # 资料

                这里没有任何可执行声明。
                """);
        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();
        return new SkillController(registry, new SkillToolExecutorFactory(mock(ChatClient.class)));
    }

    @Test
    @DisplayName("用户端目录返回全部技能（含非可执行技能），可执行标记与工具名如实暴露")
    void availableCatalogReturnsEverySkillWithExecutableFlag(@TempDir Path root) throws IOException {
        Result<List<SkillDefinitionResponse>> result = controller(root).listAvailableSkills();

        assertThat(result.getCode()).isEqualTo("SUCCESS");
        assertThat(result.getData()).extracting(SkillDefinitionResponse::getName)
                .containsExactly("codeagent", "readme");
        SkillDefinitionResponse executableSkill = result.getData().get(0);
        assertThat(executableSkill.isExecutable()).isTrue();
        assertThat(executableSkill.getTitle()).isEqualTo("Code Agent");
        assertThat(executableSkill.getToolName()).isEqualTo("codeagent_transform");
        assertThat(result.getData().get(1).isExecutable()).isFalse();
    }

    @Test
    @DisplayName("用户端目录不含 SKILL.md 正文——序列化后的响应里搜不到正文内容")
    void availableCatalogNeverLeaksSkillBody(@TempDir Path root) throws IOException {
        Result<List<SkillDefinitionResponse>> result = controller(root).listAvailableSkills();

        String json = new ObjectMapper().writeValueAsString(result.getData());
        assertThat(json).doesNotContain(SKILL_BODY);

        // 结构层再钉一道：DTO 上一个正文/来源路径字段都不该出现，防止后续顺手加上
        assertThat(Arrays.stream(SkillDefinitionResponse.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("content", "sourcePath");
    }

    @Test
    @DisplayName("管理端详情接口照旧返回正文（两条通路的信息面差异是有意的）")
    void managementDetailStillReturnsBody(@TempDir Path root) throws IOException {
        Result<SkillDetailResponse> result = controller(root).getSkill("codeagent");

        assertThat(result.getData().getContent()).contains(SKILL_BODY);
        assertThat(result.getData().getToolName()).isEqualTo("codeagent_transform");
    }

    private static void writeSkill(Path root, String name, String markdown) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
    }
}
