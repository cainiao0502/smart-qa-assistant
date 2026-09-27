package com.nailinai.ragent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PromptBudget} 的单元测试。
 *
 * <p>重点锁死三条性质：
 * <ol>
 *   <li><b>未超限时零改动</b>——这是引入预算组件最大的安全前提：正常请求的提示词
 *       必须逐字不变，否则「加个预算」会静默改变所有在线行为；</li>
 *   <li><b>低优先级先被牺牲</b>——降级顺序必须确定且可复现，不能随机丢；</li>
 *   <li><b>裁剪留痕</b>——被截断的段落必须带省略标记，整段丢弃则不留空标记
 *       （避免模型把「没看到」误判成「不存在」）。</li>
 * </ol>
 *
 * <p>测试里的 token 数使用中文占位串 {@code "字".repeat(n)}：{@code TokenEstimateUtils}
 * 对 CJK 字符按 1 token/字符估算，因此字符串长度即 token 数，断言可以写成精确等式。
 */
class PromptBudgetTest {

    private static String cjk(int tokens) {
        return "字".repeat(tokens);
    }

    private static PromptBudget.Policy exactBudget(int tokens) {
        // safetyRatio=1.0 且无预留：预算恰好等于窗口，便于精确断言
        return new PromptBudget.Policy(tokens, 0, 0, 1.0);
    }

    @Test
    @DisplayName("未超限时是完全的 no-op：内容逐字不变、不重排、不标记降级")
    void underBudget_isNoOp() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, cjk(100)),
                PromptBudget.Section.of("history", PromptBudget.P_HISTORY, cjk(100)),
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, cjk(50))
        );

        PromptBudget.Result result = PromptBudget.assemble(exactBudget(10_000), sections);

        assertThat(result.degraded()).isFalse();
        assertThat(result.summary()).isEmpty();
        assertThat(result.usedTokens()).isEqualTo(250);
        assertThat(result.kept()).allSatisfy(kept -> {
            assertThat(kept.truncated()).isFalse();
            assertThat(kept.originalTokens()).isEqualTo(kept.keptTokens());
        });
        assertThat(result.groupText("retrieval")).isEqualTo(cjk(100) + "\n\n" + cjk(50));
        assertThat(result.groupText("history")).isEqualTo(cjk(100));
    }

    @Test
    @DisplayName("超限时高优先级段落拿满，剩余额度回流给低优先级段落（water-filling 不留浪费）")
    void overBudget_keepsHighestPriorityIntactAndRefundsRemaining() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, cjk(600)),
                PromptBudget.Section.of("steps", PromptBudget.P_STEPS, cjk(600))
        );

        PromptBudget.Result result = PromptBudget.assemble(exactBudget(700), sections);

        PromptBudget.Kept retrieval = result.kept().get(0);
        PromptBudget.Kept steps = result.kept().get(1);

        // 检索切片（权重 6）按比例本应只有 600×6/7≈514，但它需求只有 600：
        // 满足后额度退还，最终完整保留——这正是 water-filling 与「按比例一刀切」的区别
        assertThat(retrieval.truncated()).isFalse();
        assertThat(retrieval.keptTokens()).isEqualTo(600);
        assertThat(steps.truncated()).isTrue();
        assertThat(steps.keptTokens()).isEqualTo(100);
        assertThat(result.usedTokens()).isEqualTo(700);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    @DisplayName("预算趋零时最低优先级先归零（被整段丢弃），高优先级仍留有内容")
    void lowestPriorityReachesZeroFirst() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("catalog", PromptBudget.P_CATALOG, cjk(600)),
                PromptBudget.Section.of("steps", PromptBudget.P_STEPS, cjk(600))
        );

        // 预算 2、权重和 3：catalog(权重2) 得 1，steps(权重1) 得 0
        PromptBudget.Result result = PromptBudget.assemble(exactBudget(2), sections);

        PromptBudget.Kept catalog = result.kept().get(0);
        PromptBudget.Kept steps = result.kept().get(1);

        assertThat(steps.dropped()).isTrue();
        assertThat(steps.keptTokens()).isZero();
        assertThat(steps.content()).isEmpty();
        assertThat(catalog.dropped()).isFalse();
        assertThat(catalog.keptTokens()).isPositive();
    }

    @Test
    @DisplayName("被截断的段落带省略标记；该分组仍能被拼回")
    void truncatedSection_carriesOmissionNotice() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("steps", PromptBudget.P_STEPS, cjk(600))
        );

        PromptBudget.Result result = PromptBudget.assemble(exactBudget(100), sections);

        PromptBudget.Kept steps = result.kept().get(0);
        assertThat(steps.truncated()).isTrue();
        assertThat(steps.keptTokens()).isEqualTo(100);
        assertThat(steps.content())
                .startsWith(cjk(100))
                .contains("因超出上下文预算省略 500 字符");
        assertThat(result.groupText("steps")).contains("因超出上下文预算省略");
        assertThat(result.summary()).contains("steps 600→100 tokens");
    }

    @Test
    @DisplayName("预算为 0 时全部段落被丢弃，且不留下空标记（避免拼出无意义提示词）")
    void zeroBudget_dropsAllSectionsWithoutResidue() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, cjk(300)),
                PromptBudget.Section.of("steps", PromptBudget.P_STEPS, cjk(300))
        );

        // 窗口 1、输出预留 1 → 可用 0
        PromptBudget.Result result = PromptBudget.assemble(new PromptBudget.Policy(1, 1, 0, 1.0), sections);

        assertThat(result.budgetTokens()).isZero();
        assertThat(result.usedTokens()).isZero();
        assertThat(result.degraded()).isTrue();
        assertThat(result.kept()).allSatisfy(kept -> {
            assertThat(kept.dropped()).isTrue();
            assertThat(kept.content()).isEmpty();
        });
        assertThat(result.groupText("retrieval")).isEmpty();
        assertThat(result.summary()).doesNotContain("因超出上下文预算省略");
    }

    @Test
    @DisplayName("groupText 按输入顺序拼回同组段落，分隔符为两个换行")
    void groupText_preservesInputOrderAndDelimiter() {
        List<PromptBudget.Section> sections = List.of(
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, "A"),
                PromptBudget.Section.of("history", PromptBudget.P_HISTORY, "B"),
                PromptBudget.Section.of("retrieval", PromptBudget.P_RETRIEVAL, "C")
        );

        PromptBudget.Result result = PromptBudget.assemble(exactBudget(10_000), sections);

        assertThat(result.groupText("retrieval")).isEqualTo("A\n\nC");
        assertThat(result.groupText("history")).isEqualTo("B");
        assertThat(result.groupText("catalog")).isEmpty();
    }

    @Test
    @DisplayName("预算 = (窗口 − 输出预留 − 输入预留) × 安全比例；非法安全比例回落 0.9")
    void policy_computesBudgetAfterReservingBothSides() {
        assertThat(new PromptBudget.Policy(1000, 100, 200, 0.9).budgetTokens()).isEqualTo(630);
        // 预留吃掉整个窗口时归零，而不是退化成一个 token 的残渣
        assertThat(new PromptBudget.Policy(100, 100, 0, 1.0).budgetTokens()).isZero();
        assertThat(new PromptBudget.Policy(100, 0, 200, 1.0).budgetTokens()).isZero();
        // 安全比例越界回落 0.9
        assertThat(new PromptBudget.Policy(1000, 0, 0, 1.5).budgetTokens()).isEqualTo(900);
        assertThat(new PromptBudget.Policy(1000, 0, 0, 0).budgetTokens()).isEqualTo(900);
        // 不限预算策略：任何规模的内容都不会被裁剪
        assertThat(PromptBudget.Policy.unbounded().budgetTokens()).isPositive();
    }

    @Test
    @DisplayName("按 token 截断不会把代理对切成孤立代理项（emoji 安全）")
    void truncateToTokens_neverSplitsSurrogatePair() {
        String text = "😀".repeat(8);

        String truncated = PromptBudget.truncateToTokens(text, 1);

        assertThat(truncated).isNotEmpty();
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
        // 长度必为偶数：每个 emoji 占两个 char，成对出现
        assertThat(truncated.length() % 2).isZero();
    }

    @Test
    @DisplayName("空段落集合与空策略参数不会抛出，返回空结果")
    void edgeCases_areSafe() {
        assertThat(PromptBudget.assemble(exactBudget(100), List.of()).kept()).isEmpty();
        assertThat(PromptBudget.assemble(exactBudget(100), null).kept()).isEmpty();
        assertThatThrownBy(() -> PromptBudget.assemble(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
