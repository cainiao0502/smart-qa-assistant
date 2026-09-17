package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.AgentRuntimeResult;
import com.nailinai.ragent.agent.dto.AgentStep;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.util.PromptBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class FinalAnswerComposer {

    private final PromptBuilder promptBuilder;
    private final DocumentService documentService;
    private final ChatClient chatClient;
    private final SkillRegistry skillRegistry;

    public FinalAnswerComposer(PromptBuilder promptBuilder,
                               DocumentService documentService,
                               ChatClient chatClient,
                               SkillRegistry skillRegistry) {
        this.promptBuilder = promptBuilder;
        this.documentService = documentService;
        this.chatClient = chatClient;
        this.skillRegistry = skillRegistry;
    }

    public String buildPrompt(ChatRequest request,
                              List<ChatMessage> history,
                              AgentRuntimeResult runtimeResult) {
        String supplementalContext = buildSupplementalContext(
                runtimeResult.getSupplementalContexts(),
                runtimeResult.getSteps(),
                runtimeResult.getFinalInstruction()
        );

        return promptBuilder.buildPrompt(
                request.getQuestion(),
                history,
                runtimeResult.getRetrievalResult().getChunks(),
                supplementalContext,
                buildDocumentCatalog(request.getKbId()),
                // 渐进式披露：这里只带技能目录；模型调 load_skill 的正文已随 supplementalContexts 进入
                skillRegistry.renderSkillCatalog(request.getSkillNames()),
                request.getKbId() != null
        );
    }

    public String composeAnswer(String finalPrompt) {
        String answer = chatClient.chat(finalPrompt);
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

    private String buildSupplementalContext(List<String> supplementalContexts,
                                            List<AgentStep> steps,
                                            String finalInstruction) {
        List<String> sections = new ArrayList<>();
        if (supplementalContexts != null) {
            sections.addAll(supplementalContexts.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList());
        }

        if (steps != null && !steps.isEmpty()) {
            sections.add("Agent step observations:\n" + steps.stream()
                    .map(step -> "- step %d [%s] %s".formatted(
                            step.getStepIndex(),
                            step.getStepType(),
                            StringUtils.hasText(step.getObservationSummary()) ? step.getObservationSummary() : "No observation"
                    ))
                    .collect(Collectors.joining("\n")));
        }

        if (StringUtils.hasText(finalInstruction)) {
            sections.add("Final answering instruction:\n" + finalInstruction.trim());
        }

        return sections.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
    }
}
