package com.nailinai.ragent.agent.tool;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code load_skill} 工具的单测：Agent Skills 渐进式披露的第二层入口。
 *
 * <p>正文进入上下文的唯一正当通路是模型主动调用本工具——
 * 这里锁住三件事：正文完整进入 supplementalContext、未知技能诚实失败、
 * 工具描述自带技能目录（第一层元数据的载体）。</p>
 */
class SkillLoadToolExecutorTest {

    @TempDir
    Path skillRoot;

    @Test
    @DisplayName("加载成功：正文进入 supplementalContext 供最终回答使用")
    void execute_shouldReturnSkillBodyAsSupplementalContext() throws IOException {
        writeSkill("deploy-flow", "---\nname: deploy-flow\ndescription: 部署流程\n---\n\n第一步，检查配置。第二步，灰度发布。");
        SkillLoadToolExecutor executor = new SkillLoadToolExecutor(registry());

        var result = executor.execute(Map.of("name", "deploy-flow"), ToolContext.builder().build());

        assertThat(result.getSupplementalContext()).contains("灰度发布");
        assertThat(result.getObservation()).contains("灰度发布");
        assertThat(result.getSummary()).contains("deploy-flow");
        assertThat(result.getTrace().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("加载不存在的技能：抛 NOT_FOUND 诚实失败，而不是返回空正文")
    void execute_shouldFailHonestlyForUnknownSkill() throws IOException {
        writeSkill("real-skill", "正文");
        SkillLoadToolExecutor executor = new SkillLoadToolExecutor(registry());

        assertThatThrownBy(() -> executor.execute(Map.of("name", "not-exist"), ToolContext.builder().build()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("工具描述携带全部技能目录——模型据此自主决定是否加载")
    void description_shouldListInstalledSkills() throws IOException {
        writeSkill("pdf-guide", "---\nname: pdf-guide\ndescription: PDF 表单填写指引\n---\n\n正文略");
        SkillLoadToolExecutor executor = new SkillLoadToolExecutor(registry());

        assertThat(executor.getDescription()).contains("pdf-guide").contains("PDF 表单填写指引");
        assertThat(executor.getParametersSchema().toString()).contains("name");
    }

    private SkillRegistry registry() {
        SkillRegistry registry = new SkillRegistry(skillRoot.toString());
        registry.reload();
        return registry;
    }

    private void writeSkill(String name, String markdown) throws IOException {
        Path dir = skillRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
    }
}
