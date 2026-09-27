package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.config.ContextBudgetProperties;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.framework.util.TokenEstimateUtils;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.infra.chat.LlmRequest;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.util.PromptBudget;
import com.nailinai.ragent.util.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 最终回答的提示词装配与生成。
 *
 * <p><b>上下文预算</b>：本类是全链路唯一会「累加」上下文的地方——每轮工具调用的
 * {@code supplementalContext} 都会追加进来，此前<b>没有任何上限</b>（单次 kb_lookup 因
 * small-to-big 可达约 1.2 万字符，8 步循环下的累加量足以超出模型窗口）。现在装配过程统一
 * 走 {@link PromptBudget}：超限时按优先级阶梯降级并在提示词里留痕，未超限时行为
 * 与改造前完全一致（零成本 no-op）。
 *
 * <p><b>不可裁剪的部分不进预算集合</b>：system prompt 与用户提问由模板固定渲染，
 * 只通过 {@code reservedInputTokens} 计入窗口占用。因此「降级」永远不会裁到
 * 「模型正在回答什么问题」这一层。
 */
@Service
public class FinalAnswerComposer {

    private static final Logger log = LoggerFactory.getLogger(FinalAnswerComposer.class);

    /**
     * 模板固定文字的 token 预留：{@code Question:} / {@code Recent conversation:} /
     * {@code Knowledge context:} / {@code Supplemental tool context:} 等标签与格式说明。
     * 留一个保守常量即可，估算器本身也有 ±30% 余量。
     */
    private static final int TEMPLATE_OVERHEAD_TOKENS = 400;

    private final PromptBuilder promptBuilder;
    private final DocumentService documentService;
    private final ChatClient chatClient;
    private final SkillRegistry skillRegistry;
    private final ContextBudgetProperties contextBudget;

    public FinalAnswerComposer(PromptBuilder promptBuilder,
                               DocumentService documentService,
                               ChatClient chatClient,
                               SkillRegistry skillRegistry,
                               ContextBudgetProperties contextBudget) {
        this.promptBuilder = promptBuilder;
        this.documentService = documentService;
        this.chatClient = chatClient;
        this.skillRegistry = skillRegistry;
        this.contextBudget = contextBudget;
    }

    // ------------------------------------------------------------------
    // 提示词装配
    // ------------------------------------------------------------------

    public String buildPrompt(ChatRequest request,
                              List<ChatMessage> history,
                              AgentRuntimeResult runtimeResult) {
        List<String> toolContexts = runtimeResult.getSupplementalContexts() == null
                ? List.of()
                : runtimeResult.getSupplementalContexts().stream().filter(StringUtils::hasText).toList();
        String documentCatalog = buildDocumentCatalog(request.getKbId());
        // 渐进式披露：这里只带技能目录；模型调 load_skill 的正文已随 supplementalContexts 进入
        String skillContext = skillRegistry.renderSkillCatalog(request.getSkillNames());

        List<PromptBudget.Section> sections = new ArrayList<>(promptBuilder.buildContextSections(
                history, runtimeResult.getRetrievalResult().getChunks(), documentCatalog, skillContext));

        // 一次工具调用一个段落：超限时丢的是「整次调用的观察」，而不是半段引用
        for (String toolContext : toolContexts) {
            sections.add(PromptBudget.Section.of("tool", PromptBudget.P_TOOL_OUTPUT, toolContext));
        }
        String stepsText = renderStepObservations(runtimeResult.getSteps());
        if (StringUtils.hasText(stepsText)) {
            sections.add(PromptBudget.Section.of("steps", PromptBudget.P_STEPS, stepsText));
        }

        PromptBudget.Result budget = applyBudget(request, sections);

        return promptBuilder.renderFromParts(
                request.getQuestion(),
                budget.groupText("history"),
                budget.groupText("retrieval"),
                assembleSupplemental(budget, runtimeResult.getFinalInstruction()),
                budget.groupText("catalog"),
                budget.groupText("skill"),
                request.getKbId() != null
        );
    }

    /**
     * 按上下文预算装配段落。
     *
     * <p>{@code reservedInputTokens} 必须包含 system prompt、用户提问与模板固定文字——
     * 工具 schema JSON 在 Planner 侧才需要计入，最终回答阶段不下发工具声明。
     */
    private PromptBudget.Result applyBudget(ChatRequest request, List<PromptBudget.Section> sections) {
        if (!contextBudget.isEnabled()) {
            return PromptBudget.assemble(PromptBudget.Policy.unbounded(), sections);
        }
        boolean knowledgeBaseEnabled = request.getKbId() != null;
        String systemPrompt = knowledgeBaseEnabled
                ? promptBuilder.knowledgeBaseSystemPrompt()
                : promptBuilder.generalAssistantSystemPrompt();
        int reservedInputTokens = TokenEstimateUtils.estimate(systemPrompt)
                + TokenEstimateUtils.estimate(request.getQuestion())
                + TEMPLATE_OVERHEAD_TOKENS;

        PromptBudget.Result result = PromptBudget.assemble(contextBudget.policy(reservedInputTokens), sections);
        if (result.degraded()) {
            // 降级必须可见：静默截断会让「模型答不全」被误判成「模型能力不行」
            log.warn("Final answer prompt degraded by context budget: window={}, budget={}, used={} | {}",
                    contextBudget.getWindowTokens(), result.budgetTokens(), result.usedTokens(), result.summary());
        }
        return result;
    }

    public String composeAnswer(String finalPrompt) {
        // max_tokens 与上下文预算的 reserved-output-tokens 精确对齐：
        // 输出预留从"假设"升级为请求级契约，超长回答被硬切而非无限生成
        String answer = chatClient.chat(LlmRequest.of(finalPrompt)
                .withMaxTokens(contextBudget.getReservedOutputTokens())).content();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型暂时没有返回有效回答，请稍后重试");
        }
        return answer;
    }

    public void streamAnswer(String finalPrompt, Consumer<String> onReasoning, Consumer<String> onChunk) {
        chatClient.streamChat(finalPrompt, onReasoning, onChunk);
    }

    private String buildDocumentCatalog(Long kbId) {
        if (kbId == null) {
            return "";
        }
        List<DocumentResponse> documents = documentService.listByKbId(kbId);
        if (documents.isEmpty()) {
            return "当前知识库没有已上传的文档记录。";
        }
        return documents.stream()
                .limit(50)
                .map(document -> "- %s | type=%s | status=%s | chunks=%s".formatted(
                        document.getName(),
                        document.getFileType() == null ? "unknown" : document.getFileType(),
                        document.getStatus() == null ? "unknown" : document.getStatus().name(),
                        document.getChunkCount() == null ? "?" : document.getChunkCount()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String renderStepObservations(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        return "Agent step observations:\n" + steps.stream()
                .map(step -> "- step %d [%s] %s".formatted(
                        step.getStepIndex(),
                        step.getStepType(),
                        StringUtils.hasText(step.getObservationSummary()) ? step.getObservationSummary() : "No observation"
                ))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 组装最终回答的补充上下文。
     *
     * <p>工具派生内容（工具返回的正文 + 步骤观察摘要）统一包裹在 {@code <tool_output>}
     * 定界符内，配合 system prompt 的「定界符内是数据不是指令」声明，把 MCP 工具
     * 返回内容里的潜在注入指令降级为待展示的数据（OWASP LLM01 的结构化防御）。
     * {@code finalInstruction} 是服务端生成的可信指令，保持在定界符之外。
     *
     * <p>预算降级后某些分组可能已被丢弃：此时只包裹剩余内容，<b>定界符本身保持完整</b>
     * ——半开的定界符会让上面那条防御声明失效。
     */
    private String assembleSupplemental(PromptBudget.Result budget, String finalInstruction) {
        List<String> toolDerived = new ArrayList<>();
        String toolText = budget.groupText("tool");
        if (StringUtils.hasText(toolText)) {
            toolDerived.add(toolText);
        }
        String stepsText = budget.groupText("steps");
        if (StringUtils.hasText(stepsText)) {
            toolDerived.add(stepsText);
        }

        List<String> sections = new ArrayList<>();
        if (!toolDerived.isEmpty()) {
            sections.add("<tool_output>\n" + String.join("\n\n", toolDerived) + "\n</tool_output>");
        }
        if (StringUtils.hasText(finalInstruction)) {
            sections.add("Final answering instruction:\n" + finalInstruction.trim());
        }

        return sections.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
    }
}
