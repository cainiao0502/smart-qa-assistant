package com.nailinai.ragent.util;

import com.nailinai.ragent.framework.util.TokenEstimateUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * LLM 请求的输入预算分配器（budget-based context packing）。
 *
 * <p><b>解决的问题</b>：本项目此前有五个上下文来源（历史 / 检索切片 / 知识库目录 / 工具补充
 * 上下文 / 步骤观察），各自按「条数」或「字符数」独立裁剪，但<b>没有任何一处计算总量</b>。
 * 结果是系统不知道自己会不会超出模型窗口，只能等供应商返回 400 才知道；而且各处的裁剪口径
 * 彼此不可加（4 条 vs 800 字符 vs 6000 token），调参时无法推理。
 *
 * <p><b>设计参照</b>：LlamaIndex 的 {@code ContextAssembler} / LangChain 的 token-budget
 * packing。核心是两件事：
 * <ol>
 *   <li><b>显式预算</b>：{@link Policy} 把「模型窗口 − 输出预留 − 输入预留」剩下的部分按
 *       安全比例作为可用预算。输出预留（{@code max_tokens}）与输入预留（system prompt +
 *       工具 schema JSON）都必须计入——工具声明经常能占几千 token，是常见漏算项。</li>
 *   <li><b>显式降级阶梯</b>：超限时按优先级分配额度，低优先级的段落先被牺牲。分配采用
 *       water-filling（按权重迭代满足），避免出现「预算还没用完就已在截断」的荒谬结果。</li>
 * </ol>
 *
 * <p><b>不可裁剪的内容不进本类的段落集合。</b>system prompt 与用户提问这类「裁了就答非所问」
 * 的部分，应计入 {@link Policy#reservedInputTokens()} 而<b>不要</b>作为 {@link Section} 传入。
 * 这比「传进来再标记为不可裁」更安全——它不给「权重算错导致被裁」留下任何可能。
 *
 * <p><b>关键性质：未超限时是完全的 no-op。</b>总 token ≤ 预算时原样返回输入段落，
 * 不重排、不修改任何字符。因此引入本类不会改变正常请求的提示词内容，只会在原本会
 * 400 的场景下给出确定的降级行为。
 *
 * <p><b>留痕原则</b>：被裁剪的段落一律追加 {@code [本节因超出上下文预算省略 N 字符]} 标记。
 * 不静默丢弃是 {@code ObservationBuilder} 已经确立的约定（"工具层给足真实内容，截断时显式
 * 告知"），本类把这套约定从「单条工具观察」上提到「整体装配」层——否则模型会把
 * 「自己没看到」误判成「上下文里不存在」，直接产生幻觉。
 */
public final class PromptBudget {

    // ------------------------------------------------------------------
    // 优先级：数值越大越重要，同时作为 water-filling 的权重（必须 > 0）
    // ------------------------------------------------------------------

    /** 步骤观察：最可牺牲——它是对已发生动作的描述，工具原文才是证据 */
    public static final int P_STEPS = 1;
    /** 知识库目录：只是"库里有哪些文档"的可用性提示，不是回答依据 */
    public static final int P_CATALOG = 2;
    /** 工具补充上下文：证据，但可逐次调用丢弃 */
    public static final int P_TOOL_OUTPUT = 3;
    /** 技能目录：属指令而非证据，篇幅小，尽量保留 */
    public static final int P_SKILL = 4;
    /** 对话历史：多轮指代依赖它，但可以只留最近几轮 */
    public static final int P_HISTORY = 5;
    /** 检索切片：RAG 的核心证据，最不该牺牲的一档 */
    public static final int P_RETRIEVAL = 6;

    private static final String OMISSION_TEMPLATE = "\n[本节因超出上下文预算省略 %d 字符]";

    private PromptBudget() {
    }

    // ------------------------------------------------------------------
    // 预算策略
    // ------------------------------------------------------------------

    /**
     * 单次请求的输入预算策略。
     *
     * @param windowTokens         模型上下文窗口总 token 数（多候选路由时应取最小值，按最保守的算）
     * @param reservedOutputTokens 为生成预留的 token 数（即请求里的 {@code max_tokens}）
     * @param reservedInputTokens  预算之外仍会占用窗口的输入：system prompt + 工具 schema JSON
     *                             + 用户提问。这些内容不会被本类裁剪
     * @param safetyRatio          安全比例，默认 0.9；为粗粒度估算（{@code TokenEstimateUtils}
     *                             偏差约 ±30%）留出余量
     */
    public record Policy(int windowTokens,
                         int reservedOutputTokens,
                         int reservedInputTokens,
                         double safetyRatio) {

        public Policy {
            windowTokens = Math.max(1, windowTokens);
            reservedOutputTokens = Math.max(0, reservedOutputTokens);
            reservedInputTokens = Math.max(0, reservedInputTokens);
            if (safetyRatio <= 0 || safetyRatio > 1) {
                safetyRatio = 0.9;
            }
        }

        /**
         * 可分配给段落内容的 token 预算。
         *
         * <p>预留项已吃掉整个窗口时返回 0——此时应该丢弃全部可裁段落（宁可只留
         * system + 提问，也不要靠 1 个 token 的残渣拼出无意义的提示词）。
         */
        public int budgetTokens() {
            int available = windowTokens - reservedOutputTokens - reservedInputTokens;
            return Math.max(0, (int) Math.floor(available * safetyRatio));
        }

        /** 不限预算：用于「预算开关关闭」时保持调用结构一致的 no-op 策略 */
        public static Policy unbounded() {
            return new Policy(Integer.MAX_VALUE / 2, 0, 0, 1.0);
        }
    }

    // ------------------------------------------------------------------
    // 段落与结果
    // ------------------------------------------------------------------

    /**
     * 待装配的段落。
     *
     * @param group    分组名（同一分组的多段会在 {@link Result#groupText} 里按序拼回）
     * @param priority 优先级，见 {@code P_*} 常量
     * @param content  段落正文
     */
    public record Section(String group, int priority, String content) {

        public static Section of(String group, int priority, String content) {
            return new Section(group, priority, content == null ? "" : content);
        }

        int tokens() {
            return TokenEstimateUtils.estimate(content);
        }
    }

    /** 单个段落最终被保留的内容与用量统计 */
    public record Kept(String group, int priority, String content, int originalTokens, int keptTokens) {

        public boolean truncated() {
            return keptTokens < originalTokens;
        }

        public boolean dropped() {
            return keptTokens == 0 && originalTokens > 0;
        }
    }

    /**
     * 装配结果。
     *
     * @param kept         各段落最终保留的内容（顺序与输入一致）
     * @param budgetTokens 本次可用预算
     * @param usedTokens   实际使用 token
     */
    public record Result(List<Kept> kept, int budgetTokens, int usedTokens) {

        public Result {
            kept = List.copyOf(kept == null ? List.of() : kept);
        }

        /** 是否发生了任何降级（有段落被裁剪或丢弃） */
        public boolean degraded() {
            return kept.stream().anyMatch(Kept::truncated);
        }

        /** 把某个分组的所有段落按原顺序拼回（分隔符与装配时保持一致） */
        public String groupText(String group) {
            StringJoiner joiner = new StringJoiner("\n\n");
            for (Kept section : kept) {
                if (section.group().equals(group) && !section.content().isBlank()) {
                    joiner.add(section.content());
                }
            }
            return joiner.toString();
        }

        /**
         * 降级摘要，供调用方打日志。未降级时返回空串。
         *
         * <p>形如：{@code catalog 1200→0 tokens (dropped); tool 12000→4000 tokens}
         */
        public String summary() {
            StringJoiner joiner = new StringJoiner("; ");
            for (Kept section : kept) {
                if (!section.truncated()) {
                    continue;
                }
                joiner.add("%s %d→%d tokens%s".formatted(
                        section.group(), section.originalTokens(), section.keptTokens(),
                        section.dropped() ? " (dropped)" : ""));
            }
            return joiner.toString();
        }
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /**
     * 按预算装配段落。
     *
     * <p>算法：总用量未超预算直接原样返回；超限时剩余预算按优先级权重 water-filling 分配给
     * 各段落，最后逐段按额度截断并留痕。
     */
    public static Result assemble(Policy policy, List<Section> sections) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (sections == null || sections.isEmpty()) {
            return new Result(List.of(), policy.budgetTokens(), 0);
        }

        int budget = policy.budgetTokens();
        int totalTokens = 0;
        for (Section section : sections) {
            totalTokens += section.tokens();
        }

        // 未超限：完整 no-op（不重排、不改写任何字符）
        if (totalTokens <= budget) {
            List<Kept> kept = new ArrayList<>(sections.size());
            for (Section section : sections) {
                int tokens = section.tokens();
                kept.add(new Kept(section.group(), section.priority(), section.content(), tokens, tokens));
            }
            return new Result(kept, budget, totalTokens);
        }

        int[] quota = waterFill(sections, budget);
        List<Kept> kept = new ArrayList<>(sections.size());
        int used = 0;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            int original = section.tokens();
            int allowed = quota[index];
            if (allowed >= original) {
                kept.add(new Kept(section.group(), section.priority(), section.content(), original, original));
                used += original;
                continue;
            }
            String trimmed = truncateToTokens(section.content(), allowed);
            int keptTokens = TokenEstimateUtils.estimate(trimmed);
            if (keptTokens == 0) {
                // 整段丢弃时不追加留痕：该分组在 groupText 里本就不会出现，
                // 留一条空标记只会白占位置
                kept.add(new Kept(section.group(), section.priority(), "", original, 0));
                continue;
            }
            kept.add(new Kept(section.group(), section.priority(),
                    trimmed + OMISSION_TEMPLATE.formatted(original - keptTokens), original, keptTokens));
            used += keptTokens;
        }
        return new Result(kept, budget, used);
    }

    /**
     * 按优先级权重分配预算（water-filling）。
     *
     * <p>思路：反复按权重比例给仍未满足的段落分额度；某段落分到的额度已达到其原始用量时
     * 即视为满足并退出竞争，其实际用量从可用额度中扣除，剩下的额度留给其他段落。
     * 与「按比例一刀切」的区别在于：<b>额度不会停留在用不完的段落上被浪费</b>。
     *
     * @return 与 {@code sections} 下标对齐的额度数组
     */
    private static int[] waterFill(List<Section> sections, int budget) {
        int count = sections.size();
        int[] quota = new int[count];
        if (count == 0 || budget <= 0) {
            return quota;
        }
        boolean[] satisfied = new boolean[count];
        int remaining = budget;
        while (true) {
            int weightSum = 0;
            for (int index = 0; index < count; index++) {
                if (!satisfied[index]) {
                    weightSum += weight(sections.get(index));
                }
            }
            if (weightSum <= 0 || remaining <= 0) {
                break;
            }
            List<Integer> newlySatisfied = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                if (satisfied[index]) {
                    continue;
                }
                Section section = sections.get(index);
                int tokens = section.tokens();
                int share = (int) ((long) remaining * weight(section) / weightSum);
                if (share >= tokens) {
                    quota[index] = tokens;
                    newlySatisfied.add(index);
                } else {
                    quota[index] = share;
                }
            }
            if (newlySatisfied.isEmpty()) {
                break;
            }
            for (int index : newlySatisfied) {
                satisfied[index] = true;
                remaining -= quota[index];
            }
        }
        return quota;
    }

    private static int weight(Section section) {
        return Math.max(1, section.priority());
    }

    /**
     * 按 token 额度截取文本前缀。
     *
     * <p>用二分找「估算 token 数不超过额度」的最长前缀。{@code TokenEstimateUtils} 只是估算器
     * （偏差约 ±30%），这里不追求精确，只要求单调可复现。
     */
    static String truncateToTokens(String text, int targetTokens) {
        if (text == null || text.isEmpty() || targetTokens <= 0) {
            return "";
        }
        if (TokenEstimateUtils.estimate(text) <= targetTokens) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimateUtils.estimate(text.substring(0, mid)) <= targetTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        int cut = low;
        // 切点不得落在代理对中间，否则产生孤立代理项
        if (cut > 0 && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return text.substring(0, cut);
    }
}
