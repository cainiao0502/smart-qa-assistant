package com.nailinai.ragent.skill;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.agent.tool.ToolExecutor;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.infra.chat.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SkillToolExecutorFactory {

    private final ChatClient chatClient;

    public SkillToolExecutorFactory(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public boolean supports(SkillDefinition skill) {
        return skill != null
                && skill.isExecutable()
                && StringUtils.hasText(skill.getToolName())
                && "llm_transform".equalsIgnoreCase(skill.getExecutorType());
    }

    public ToolExecutor create(SkillDefinition skill) {
        return new ToolExecutor() {
            @Override
            public String getToolName() {
                return skill.getToolName();
            }

            @Override
            public String getDisplayName() {
                return skill.getTitle();
            }

            @Override
            public String getDescription() {
                return StringUtils.hasText(skill.getDescription())
                        ? skill.getDescription()
                        : "Executable skill from " + skill.getName();
            }

            @Override
            public String getSource() {
                return "skill:" + skill.getName();
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("input", Map.of("type", "string", "description", "传给该 skill 的输入文本或指令，可为空"));
                return ToolExecutor.objectSchema(properties, List.of());
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> arguments, ToolContext context) {
                long startTime = System.currentTimeMillis();
                String output = chatClient.chat(buildSkillPrompt(skill, arguments, context));
                String normalizedOutput = StringUtils.hasText(output)
                        ? output.trim()
                        : "Skill returned no content.";
                Map<String, Object> traceArguments = arguments == null
                        ? Map.of()
                        : new LinkedHashMap<>(arguments);

                return ToolExecutionResult.builder()
                        .trace(ToolCallTraceResponse.builder()
                                .toolName(getToolName())
                                .displayName(getDisplayName())
                                .source(getSource())
                                .status("SUCCESS")
                                .arguments(traceArguments)
                                .summary("Executed skill %s".formatted(skill.getName()))
                                .resultPreview(truncate(normalizedOutput, 1200))
                                .rawResult(Map.of(
                                        "skillName", skill.getName(),
                                        "toolName", getToolName(),
                                        "output", normalizedOutput
                                ))
                                .durationMs(System.currentTimeMillis() - startTime)
                                .build())
                        .summary("Executed skill %s".formatted(skill.getName()))
                        .supplementalContext(normalizedOutput)
                        .references(List.of())
                        .rawResult(Map.of(
                                "skillName", skill.getName(),
                                "output", normalizedOutput
                        ))
                        .observation(normalizedOutput)
                        .build();
            }
        };
    }

    private String buildSkillPrompt(SkillDefinition skill, Map<String, Object> arguments, ToolContext context) {
        String chunkContext = context.getRetrievedChunks() == null
                ? "(empty)"
                : context.getRetrievedChunks().stream()
                .limit(6)
                .map(this::formatChunk)
                .collect(Collectors.joining("\n\n"));
        String historyText = context.getHistory() == null
                ? "(empty)"
                : context.getHistory().stream()
                .skip(Math.max(0, context.getHistory().size() - 4))
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));

        return """
                You are executing a skill as a structured tool.
                Follow the skill instructions closely and return a concise Chinese result that can be used as tool context.
                Do not mention hidden reasoning. Do not wrap in markdown fences.

                Skill name: %s
                Skill title: %s
                Skill instructions:
                %s

                User question:
                %s

                Tool arguments:
                %s

                Recent history:
                %s

                Retrieved knowledge context:
                %s
                """.formatted(
                skill.getName(),
                skill.getTitle(),
                skill.getContent(),
                context.getRequest().getQuestion(),
                arguments == null || arguments.isEmpty() ? "(empty)" : arguments,
                historyText.isBlank() ? "(empty)" : historyText,
                chunkContext.isBlank() ? "(empty)" : chunkContext
        );
    }

    private String formatChunk(DocumentChunk chunk) {
        String source = chunk.getDocumentName() == null ? "unknown" : chunk.getDocumentName();
        return "[%s#%s] %s".formatted(source, chunk.getChunkIndex(), chunk.getChunkText());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
