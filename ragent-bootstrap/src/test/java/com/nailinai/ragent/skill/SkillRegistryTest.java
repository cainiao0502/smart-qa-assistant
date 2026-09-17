package com.nailinai.ragent.skill;

import com.nailinai.ragent.dto.request.SkillCreateRequest;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skill 注册表的单元测试。
 *
 * <p>覆盖两条容易出事、又没有任何真机路径能顺带覆盖的逻辑：
 * <ul>
 *   <li><b>可执行判定</b>——只有同时声明 {@code toolName} 与 {@code executorType} 的 skill 才会进工具表，
 *       只声明一半就被当成工具会让模型调到一个没有执行器的工具；</li>
 *   <li><b>路径穿越防护</b>——skill 名来自 HTTP 请求体，直接拼进文件路径就能写到目录外。</li>
 * </ul>
 */
class SkillRegistryTest {

    @Test
    @DisplayName("声明 toolName + executorType 的 skill 才登记为可执行工具")
    void loadSkills_shouldMarkExecutableOnlyWhenBothDeclared(@TempDir Path root) throws IOException {
        writeSkill(root, "codeagent", """
                ---
                title: Code Agent
                description: 把检索到的代码上下文转成实现建议
                toolName: codeagent_transform
                executorType: llm_transform
                ---

                # Code Agent

                先理解现有结构，再决定改动位置。
                """);
        writeSkill(root, "half-declared", """
                ---
                toolName: half_tool
                ---

                # Half
                """);

        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        assertThat(registry.listSkills()).extracting(SkillDefinition::getName)
                .containsExactly("codeagent", "half-declared");
        assertThat(registry.listExecutableSkills()).extracting(SkillDefinition::getToolName)
                .containsExactly("codeagent_transform");

        SkillDefinition codeagent = registry.getSkill("codeagent");
        assertThat(codeagent.getTitle()).isEqualTo("Code Agent");
        assertThat(codeagent.getDescription()).isEqualTo("把检索到的代码上下文转成实现建议");
        assertThat(codeagent.getExecutorType()).isEqualTo("llm_transform");
        assertThat(codeagent.isExecutable()).isTrue();
        // front matter 之后的正文才是注入提示词的内容，不能把元数据一起塞进去
        assertThat(codeagent.getContent()).contains("先理解现有结构").doesNotContain("toolName");
    }

    @Test
    @DisplayName("没有 front matter 的 skill 照样能读，标题回退取一级标题")
    void loadSkills_shouldFallBackToHeadingTitleWithoutFrontMatter(@TempDir Path root) throws IOException {
        writeSkill(root, "readme", "# 我的资料\n\n这里只是一份说明。\n");

        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        SkillDefinition skill = registry.getSkill("readme");
        assertThat(skill.getTitle()).isEqualTo("我的资料");
        assertThat(skill.getDescription()).isEqualTo("这里只是一份说明。");
        assertThat(skill.isExecutable()).isFalse();
        assertThat(skill.getToolName()).isNull();
    }

    @Test
    @DisplayName("skill 名里的路径穿越片段被规范化，落盘不会越出根目录")
    void createSkill_shouldNeutralizePathTraversalName(@TempDir Path root) {
        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        SkillDefinition created = registry.createSkill(request("../../evil", "正文"));

        assertThat(created.getName()).isEqualTo("evil");
        assertThat(root.resolve("evil").resolve("SKILL.md")).exists();
        // 关键断言：根目录之外不能出现任何东西
        assertThat(root.getParent().resolve("evil")).doesNotExist();
    }

    @Test
    @DisplayName("未开启 overwrite 时同名 skill 拒绝覆盖，避免误删他人配置")
    void createSkill_shouldRejectExistingNameWithoutOverwrite(@TempDir Path root) {
        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();
        registry.createSkill(request("demo", "第一版"));

        SkillCreateRequest again = request("demo", "第二版");
        again.setOverwrite(false);

        assertThatThrownBy(() -> registry.createSkill(again))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(registry.getSkill("demo").getContent()).contains("第一版");
    }

    @Test
    @DisplayName("取不存在的 skill 抛 NOT_FOUND，而不是返回 null 让调用方空指针")
    void getSkill_shouldThrowNotFoundForUnknownName(@TempDir Path root) {
        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        assertThatThrownBy(() -> registry.getSkill("not-exist"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("空名或纯符号名直接拒绝——它们规范化后是空串，会拼出根目录本身")
    void createSkill_shouldRejectNameThatNormalizesToEmpty(@TempDir Path root) {
        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        assertThatThrownBy(() -> registry.createSkill(request("///", "正文")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("frontmatter.name 与目录名不一致：按 Agent Skills 标准视为无效，跳过加载")
    void loadSkills_shouldSkipWhenDeclaredNameMismatchDirectoryName(@TempDir Path root) throws IOException {
        writeSkill(root, "wrong-dir", "---\nname: other-name\ntitle: X\n---\n\n# X\n");

        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        assertThat(registry.listSkills()).isEmpty();
        assertThatThrownBy(() -> registry.getSkill("wrong-dir"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("目录只含名称与描述，不携带正文——渐进式披露第一层")
    void renderSkillCatalog_shouldContainMetadataOnly(@TempDir Path root) throws IOException {
        writeSkill(root, "deploy-flow", "---\ndescription: 部署流程指引\n---\n\n# 部署流程\n\n第一步，检查配置。第二步，灰度发布。第三步，观察监控。");

        SkillRegistry registry = new SkillRegistry(root.toString());
        registry.reload();

        String catalog = registry.renderSkillCatalog(List.of("deploy-flow"));
        assertThat(catalog).contains("deploy-flow").contains("部署流程指引");
        // 正文是第二层，只能经 load_skill 进入上下文，不能混进目录
        assertThat(catalog).doesNotContain("灰度发布");
        assertThat(registry.renderSkillCatalog(List.of())).isEmpty();
    }

    private static SkillCreateRequest request(String name, String content) {
        SkillCreateRequest request = new SkillCreateRequest();
        request.setName(name);
        request.setTitle(name);
        request.setContent(content);
        return request;
    }

    private static void writeSkill(Path root, String name, String markdown) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
    }
}
