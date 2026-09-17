package com.nailinai.ragent.chat.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.infra.chat.ChatClient;
import com.nailinai.ragent.mcp.McpToolCatalog;
import com.nailinai.ragent.mcp.McpToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final McpToolCatalog mcpToolCatalog;

    public IntentClassifier(ChatClient chatClient,
                            ObjectMapper objectMapper,
                            McpToolCatalog mcpToolCatalog) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.mcpToolCatalog = mcpToolCatalog;
    }

    public IntentDecision classify(ChatRequest request) {
        if (request.getKbId() == null
                && (request.getSkillNames() == null || request.getSkillNames().isEmpty())
                && !Boolean.TRUE.equals(request.getAgentEnabled())) {
            return IntentDecision.system();
        }

        if (request.getKbId() != null
                && (request.getSkillNames() == null || request.getSkillNames().isEmpty())
                && !Boolean.TRUE.equals(request.getAgentEnabled())) {
            return IntentDecision.kb();
        }

        if (request.getKbId() == null
                && (request.getSkillNames() != null && !request.getSkillNames().isEmpty())) {
            return IntentDecision.mcp();
        }

        try {
            String prompt = buildClassificationPrompt(request);
            String response = chatClient.chat(prompt);
            return parseResponse(response, request);
        } catch (RuntimeException ex) {
            log.warn("intent classification failed, fallback to KB: {}", ex.getMessage());
            return request.getKbId() != null ? IntentDecision.kb() : IntentDecision.system();
        }
    }

    private String buildClassificationPrompt(ChatRequest request) {
        List<McpToolDefinition> availableTools = mcpToolCatalog.listAllTools();
        String toolList = availableTools.isEmpty()
                ? "(none)"
                : availableTools.stream()
                .map(tool -> "- " + tool.exposedName() + ": " + (tool.description() == null ? "" : tool.description()))
                .reduce("", (a, b) -> a + "\n" + b);

        boolean hasKb = request.getKbId() != null;
        boolean hasSkills = request.getSkillNames() != null && !request.getSkillNames().isEmpty();

        return """
                You are an intent classifier for a RAG + Agent system.
                Classify the user's question into exactly one intent.

                Available intents:
                - KB: Answer from the knowledge base using RAG retrieval.
                - MCP: Answer using external tools (MCP servers or skills).
                - SYSTEM: Direct LLM reply for general coding, writing, or planning tasks.
                - CLARIFY: The question is ambiguous and needs the user to clarify before proceeding.

                Context:
                - knowledge_base_selected: %s
                - skills_selected: %s
                - agent_enabled: %s
                - available_tools:
                %s

                Rules:
                - If a knowledge base is selected and the question asks about document content, use KB.
                - If skills or MCP tools are selected and the question needs external data, use MCP.
                - If the question is a general coding, writing, or planning request with no need for KB or tools, use SYSTEM.
                - If the question is too vague to route confidently, use CLARIFY and provide a short clarification question.
                - Return JSON only with schema: {"intent":"KB|MCP|SYSTEM|CLARIFY","clarification":"optional short question"}

                User question:
                %s
                """.formatted(
                hasKb,
                hasSkills,
                Boolean.TRUE.equals(request.getAgentEnabled()),
                toolList,
                request.getQuestion()
        );
    }

    private IntentDecision parseResponse(String response, ChatRequest request) {
        if (!StringUtils.hasText(response)) {
            return request.getKbId() != null ? IntentDecision.kb() : IntentDecision.system();
        }

        try {
            String json = extractJsonObject(response.trim());
            JsonNode root = objectMapper.readTree(json);
            String intentText = root.path("intent").asText("").trim().toUpperCase();
            String clarification = root.path("clarification").asText(null);

            Intent intent = switch (intentText) {
                case "KB" -> Intent.KB;
                case "MCP" -> Intent.MCP;
                case "SYSTEM" -> Intent.SYSTEM;
                case "CLARIFY" -> Intent.CLARIFY;
                default -> request.getKbId() != null ? Intent.KB : Intent.SYSTEM;
            };

            if (intent == Intent.CLARIFY && !StringUtils.hasText(clarification)) {
                clarification = "请问您能进一步说明一下需求吗？";
            }

            return new IntentDecision(intent, clarification);
        } catch (Exception ex) {
            log.warn("failed to parse intent classification response: {}", ex.getMessage());
            return request.getKbId() != null ? IntentDecision.kb() : IntentDecision.system();
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}