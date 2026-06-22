package com.nailinai.ragent.util;

import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are Mini Ragent, a knowledge-base assistant with optional tool support.
            Answer requirements:
            - Do not output any thinking process, reasoning trace, or <arg_key> tags.
            - Answer directly in natural, friendly Chinese.
            - Keep a warm, companionable tone. Sound like a thoughtful teammate instead of a sterile report.
            - When it fits the user's mood, you may use 1-3 light emojis to make the reply feel more alive, but do not overuse them.
            - When "Supplemental tool context" is provided, treat it as trustworthy external tool evidence for this turn.
            - When tool context is present, do not say that you cannot access the internet, cannot call external APIs, or do not have external-tool capability.
            - Prefer answering from supplemental tool context when it directly addresses the user's request.
            - Distinguish between supported facts and your own supplementary explanation:
              * Content derived from the supplied knowledge context or supplemental tool context should be presented as supported factual claims.
              * Content not directly supported by either source must be explicitly marked as supplementary explanation.
            - If the knowledge-base context is insufficient but supplemental tool context is sufficient, answer from the tool context instead of refusing.
            - Only say the available information is insufficient when both the knowledge-base context and the supplemental tool context are insufficient.
            - Always cite the source document name and chunk index when referencing knowledge-base facts.
            - Avoid sounding flat, bureaucratic, or template-like.
            """;

    private static final String DEFAULT_USER_PROMPT = """
            Question:
            {{question}}

            Recent conversation:
            {{history}}

            Knowledge context:
            {{context}}
            """;

    private String systemPromptTemplate = DEFAULT_SYSTEM_PROMPT;
    private String userPromptTemplate = DEFAULT_USER_PROMPT;

    @PostConstruct
    void loadTemplates() {
        systemPromptTemplate = loadTemplate("prompt/rag-system.txt", DEFAULT_SYSTEM_PROMPT);
        userPromptTemplate = loadTemplate("prompt/rag-user.txt", DEFAULT_USER_PROMPT);
    }

    public String buildPrompt(String question, List<ChatMessage> history, List<DocumentChunk> chunks) {
        return buildPrompt(question, history, chunks, null, null);
    }

    public String buildPrompt(String question, List<ChatMessage> history, List<DocumentChunk> chunks, String supplementalContext) {
        return buildPrompt(question, history, chunks, supplementalContext, null);
    }

    public String buildPrompt(String question,
                              List<ChatMessage> history,
                              List<DocumentChunk> chunks,
                              String supplementalContext,
                              String documentCatalog) {
        return buildPrompt(question, history, chunks, supplementalContext, documentCatalog, null);
    }

    public String buildPrompt(String question,
                              List<ChatMessage> history,
                              List<DocumentChunk> chunks,
                              String supplementalContext,
                              String documentCatalog,
                              String skillContext) {
        return buildPrompt(question, history, chunks, supplementalContext, documentCatalog, skillContext, true);
    }

    public String buildPrompt(String question,
                              List<ChatMessage> history,
                              List<DocumentChunk> chunks,
                              String supplementalContext,
                              String documentCatalog,
                              String skillContext,
                              boolean knowledgeBaseEnabled) {
        String historyText = history.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));

        String context = chunks.stream()
                .map(chunk -> {
                    String source = "[来源: " + chunk.getDocumentName()
                            + ", 片段#" + chunk.getChunkIndex()
                            + (chunk.getParagraphIndex() != null ? ", 段落#" + chunk.getParagraphIndex() : "")
                            + "]";
                    return source + "\n" + chunk.getChunkText();
                })
                .collect(Collectors.joining("\n\n"));

        String renderedUserPrompt;
        if (knowledgeBaseEnabled) {
            renderedUserPrompt = userPromptTemplate
                    .replace("{{question}}", question)
                    .replace("{{history}}", historyText)
                    .replace("{{context}}", context)
                    .replace("{{document_catalog}}", documentCatalog == null ? "" : documentCatalog);
        } else {
            renderedUserPrompt = """
                    Question:
                    %s

                    Recent conversation:
                    %s
                    """.formatted(question, historyText);
        }

        if (supplementalContext != null && !supplementalContext.isBlank()) {
            renderedUserPrompt = renderedUserPrompt.strip()
                    + "\n\nSupplemental tool context:\n"
                    + supplementalContext.strip()
                    + "\n\nUse the supplemental tool context directly when it is relevant to the user's request.";
        }

        if (skillContext != null && !skillContext.isBlank()) {
            renderedUserPrompt = renderedUserPrompt.strip()
                    + "\n\nSelected skills:\n"
                    + skillContext.strip()
                    + "\n\nFollow the skill instructions when they are relevant to the user's request.";
        }

        String systemPrompt = knowledgeBaseEnabled
                ? systemPromptTemplate.strip()
                : """
                You are Mini Ragent, a capable general-purpose assistant with optional tool support.
                Answer requirements:
                - Do not output any thinking process, reasoning trace, or <arg_key> tags.
                - Answer directly in natural, friendly Chinese.
                - Keep a warm, companionable tone.
                - When the user requests coding, drafting, planning, or creative work, do the work directly instead of discussing missing knowledge-base documents.
                - When "Supplemental tool context" is provided, treat it as trustworthy external tool evidence for this turn.
                - Do not mention a knowledge base unless the user explicitly asks about one.
                """.strip();

        return systemPrompt + "\n\n" + renderedUserPrompt.strip();
    }

    private String loadTemplate(String path, String fallback) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return fallback;
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load prompt template: " + path, exception);
        }
    }
}