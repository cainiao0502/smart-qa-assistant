package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.ChatResponse;
import com.nailinai.ragent.infra.chat.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩轮间钩子——参考 pi {@code harness/compaction} 的设计，
 * 针对本项目「单轮问答、maxSteps 小、膨胀主因是步骤观察」的场景做了裁剪。
 *
 * <p><b>策略（对应 pi 的五个可借鉴点）：</b></p>
 * <ul>
 *   <li><b>保留最近原文</b>：最近 {@code keep-recent-steps} 步逐字保留（agent 的工作记忆），
 *       plan 步骤永远原样保留（Planner 依赖它提取既有计划）；</li>
 *   <li><b>结构化摘要模板</b>：目标 / 关键发现 / 重要数据 / 待办——
 *       明确要求数据点（docId、参数、数值）原样保留，防止压缩后后续步骤拿不到引用；</li>
 *   <li><b>增量更新</b>：旧摘要文本作为输入的一部分参与重摘（对应 pi 的 previousSummary），
 *       不从头重摘全部历史；</li>
 *   <li><b>防重复</b>：待摘要集合里没有新步骤时直接跳过；</li>
 *   <li><b>预留生成空间</b>：摘要请求显式设置 maxTokens，并要求摘要总长受限。</li>
 * </ul>
 *
 * <p><b>与 pi 的差异：</b>pi 面向长任务编码（几百轮 transcript），压缩对象是整个会话树；
 * 本项目单轮问答至多 {@code maxSteps} 步，膨胀主因是工具观察，故只压缩步骤视图，
 * 会话历史由 Planner 侧「仅取最近 4 条」天然受限。摘要失败时返回 {@code null} 降级，
 * 绝不阻断主循环。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.agent.compaction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContextCompactionService implements AgentTurnHook {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionService.class);

    private static final String STEP_TYPE_PLAN = "plan";
    private static final String STEP_TYPE_COMPACTION = "compaction";

    private static final String SUMMARIZATION_SYSTEM_PROMPT = """
            你是上下文压缩助手。你的任务是阅读一次任务执行过程中的多条工具观察记录，\
            产出一份结构化摘要，供后续执行步骤的规划参考。\
            不要续写对话，不要回答用户问题，只输出摘要本身。""";

    private static final String SUMMARIZATION_USER_TEMPLATE = """
            请将以下早期步骤的观察记录压缩为结构化摘要，严格按此格式输出：

            ## 目标
            ## 关键发现
            ## 重要数据
            ## 待办与注意

            要求：
            - 文档 ID、文档名、参数、数值等后续步骤可能直接引用的数据，必须原样保留，禁止改写或省略。
            - 摘要总长度不超过 600 字。

            <observations>
            %s
            </observations>""";

    private final ChatClient chatClient;
    private final int triggerChars;
    private final int keepRecentSteps;
    private final int summaryMaxTokens;

    public ContextCompactionService(ChatClient chatClient,
                                    @Value("${app.agent.compaction.trigger-chars:6000}") int triggerChars,
                                    @Value("${app.agent.compaction.keep-recent-steps:2}") int keepRecentSteps,
                                    @Value("${app.agent.compaction.summary-max-tokens:1024}") int summaryMaxTokens) {
        this.chatClient = chatClient;
        this.triggerChars = Math.max(500, triggerChars);
        this.keepRecentSteps = Math.max(1, keepRecentSteps);
        this.summaryMaxTokens = Math.max(256, summaryMaxTokens);
    }

    @Override
    public List<AgentStep> beforeTurn(List<AgentStep> priorSteps) {
        if (priorSteps == null || priorSteps.size() <= keepRecentSteps) {
            return null;
        }

        List<AgentStep> retained = new ArrayList<>();
        List<AgentStep> candidates = new ArrayList<>();
        int keepFrom = Math.max(0, priorSteps.size() - keepRecentSteps);
        int freshObservationChars = 0;

        for (int index = 0; index < priorSteps.size(); index++) {
            AgentStep step = priorSteps.get(index);
            String type = step.getStepType() == null ? "" : step.getStepType();
            if (index >= keepFrom) {
                retained.add(step);
                continue;
            }
            if (STEP_TYPE_PLAN.equals(type)) {
                // plan 步骤承载计划状态，Planner 的 extractLatestPlan 依赖它，必须原样保留
                retained.add(step);
                continue;
            }
            candidates.add(step);
            if (!STEP_TYPE_COMPACTION.equals(type)) {
                freshObservationChars += observationLength(step);
            }
        }

        // 防重复：候选里没有新步骤（只剩旧摘要）时，无事可做
        boolean hasFreshStep = candidates.stream().anyMatch(step -> !STEP_TYPE_COMPACTION.equals(step.getStepType()));
        if (!hasFreshStep) {
            return null;
        }

        int totalChars = candidates.stream().mapToInt(this::observationLength).sum();
        if (totalChars < triggerChars) {
            return null;
        }

        String summary;
        try {
            summary = summarize(candidates);
        } catch (Exception exception) {
            // 摘要失败绝不阻断主循环：沿用原视图，仅告警
            log.warn("Context compaction failed, fallback to full step view: {}", exception.getMessage());
            return null;
        }

        List<AgentStep> view = new ArrayList<>(retained);
        AgentStep compacted = AgentStep.builder()
                .runId(candidates.get(0).getRunId())
                .stepIndex(candidates.get(0).getStepIndex())
                .stepType(STEP_TYPE_COMPACTION)
                .reason("Context compaction: " + candidates.size() + " earlier steps summarized (~" + totalChars + " chars).")
                .observationSummary(summary)
                .status("SUCCESS")
                .durationMs(0L)
                .build();
        view.add(compacted);
        view.sort(Comparator.comparingInt(step -> step.getStepIndex() == null ? Integer.MAX_VALUE : step.getStepIndex()));

        log.info("Context compacted: {} steps ({} chars) -> 1 summary; kept {} recent/plan steps verbatim.",
                candidates.size(), totalChars, retained.size());
        return List.copyOf(view);
    }

    private int observationLength(AgentStep step) {
        int length = step.getObservationSummary() == null ? 0 : step.getObservationSummary().length();
        length += step.getReason() == null ? 0 : step.getReason().length();
        return length;
    }

    /**
     * 调 LLM 生成结构化摘要。旧摘要文本（若有）一并作为输入，实现 pi 式的增量更新。
     */
    private String summarize(List<AgentStep> candidates) {
        StringBuilder observations = new StringBuilder();
        for (AgentStep step : candidates) {
            if (STEP_TYPE_COMPACTION.equals(step.getStepType())) {
                observations.append("【此前摘要（请在此基础上合并更新）】\n")
                        .append(blankAs(step.getObservationSummary(), ""))
                        .append("\n\n");
                continue;
            }
            observations.append("步骤 ").append(step.getStepIndex())
                    .append(" [").append(blankAs(step.getToolName(), "action")).append("] ");
            if (StringUtils.hasText(step.getReason())) {
                observations.append("意图：").append(step.getReason()).append("。");
            }
            observations.append("观察：").append(blankAs(step.getObservationSummary(), "（无）")).append("\n\n");
        }

        LlmRequest request = new LlmRequest(
                List.of(
                        Map.of("role", "system", "content", SUMMARIZATION_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", SUMMARIZATION_USER_TEMPLATE.formatted(observations.toString()))
                ),
                List.of(),
                "auto",
                0.2,
                summaryMaxTokens
        );
        ChatResponse response = chatClient.chat(request);
        if (response == null || !response.hasContent() || !StringUtils.hasText(response.content())) {
            throw new IllegalStateException("Summarization returned empty content");
        }
        return response.content().trim();
    }

    private String blankAs(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
