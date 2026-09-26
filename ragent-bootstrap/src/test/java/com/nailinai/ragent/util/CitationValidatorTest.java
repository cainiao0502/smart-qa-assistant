package com.nailinai.ragent.util;

import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CitationValidator 引用真实性校验的单元测试。
 *
 * <p>覆盖：合法引用保留、编造片段号剔除（保留文档名）、括号包裹整体移除、
 * 未知文档保守保留、无引用直通、多引用混合、间距/全角变体。</p>
 */
class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    @DisplayName("引用的片段号真实存在：原样保留")
    void validCitation_shouldBeKept() {
        String answer = "报销流程见（来源：aaa.txt，片段#1）。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1), ref("aaa.txt", 2));

        assertThat(validator.validateAndFix(answer, references)).isEqualTo(answer);
    }

    @Test
    @DisplayName("片段号不存在：只移除片段号，保留文档名与括号")
    void hallucinatedChunkIndex_shouldBeRemoved() {
        String answer = "依据见（来源：aaa.txt，片段#99）。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1));

        String fixed = validator.validateAndFix(answer, references);

        assertThat(fixed).isEqualTo("依据见（来源：aaa.txt）。");
        assertThat(fixed).doesNotContain("#99");
    }

    @Test
    @DisplayName("紧邻文档名的括号包裹引用整体移除，不留下空括号")
    void bareChunkCitationInBrackets_shouldBeRemovedWhole() {
        String answer = "详见 aaa.txt（片段#99）。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1));

        assertThat(validator.validateAndFix(answer, references)).isEqualTo("详见 aaa.txt。");
    }

    @Test
    @DisplayName("片段号无法归属到已知文档：保守保留不改写")
    void unattributableCitation_shouldBeKept() {
        String answer = "这个问题很复杂（片段#99），需要人工确认。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1));

        assertThat(validator.validateAndFix(answer, references)).isEqualTo(answer);
    }

    @Test
    @DisplayName("文档名不在 references 中：该引用保守保留")
    void unknownDocument_shouldBeKept() {
        String answer = "依据见（来源：unknown.pdf，片段#1）。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1));

        assertThat(validator.validateAndFix(answer, references)).isEqualTo(answer);
    }

    @Test
    @DisplayName("没有 references 时直通返回")
    void emptyReferences_shouldBypass() {
        String answer = "任何回答（来源：aaa.txt，片段#1）。";

        assertThat(validator.validateAndFix(answer, List.of())).isEqualTo(answer);
        assertThat(validator.validateAndFix(answer, null)).isEqualTo(answer);
    }

    @Test
    @DisplayName("多引用混合：合法保留、编造剔除")
    void mixedCitations_shouldFixSelectively() {
        String answer = "第一点见（来源：aaa.txt，片段#1）；第二点见（来源：task.txt，片段#9）。";
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1), ref("task.txt", 2));

        String fixed = validator.validateAndFix(answer, references);

        assertThat(fixed).isEqualTo("第一点见（来源：aaa.txt，片段#1）；第二点见（来源：task.txt）。");
    }

    @Test
    @DisplayName("「片段 # 3」间距与全角井号变体也能识别")
    void spacingAndFullWidthVariants_shouldBeRecognized() {
        List<ReferenceChunkResponse> references = List.of(ref("aaa.txt", 1));

        assertThat(validator.validateAndFix("见（来源：aaa.txt，片段 #99）。", references))
                .isEqualTo("见（来源：aaa.txt）。");
        assertThat(validator.validateAndFix("见（来源：aaa.txt，片段＃99）。", references))
                .isEqualTo("见（来源：aaa.txt）。");
    }

    private ReferenceChunkResponse ref(String documentName, int chunkIndex) {
        return ReferenceChunkResponse.builder()
                .documentName(documentName)
                .chunkIndex(chunkIndex)
                .build();
    }
}
