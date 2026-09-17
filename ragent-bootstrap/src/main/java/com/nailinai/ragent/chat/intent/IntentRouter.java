package com.nailinai.ragent.chat.intent;

import com.nailinai.ragent.agent.AgentRuntimeListener;
import com.nailinai.ragent.agent.ChatAgentOrchestrator;
import com.nailinai.ragent.agent.FinalAnswerComposer;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.MemoryService;
import com.nailinai.ragent.framework.util.JsonUtils;
import com.nailinai.ragent.util.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final ChatAgentOrchestrator chatAgentOrchestrator;
    private final FinalAnswerComposer finalAnswerComposer;
    private final MemoryService memoryService;
    private final PromptBuilder promptBuilder;

    public IntentRouter(ChatAgentOrchestrator chatAgentOrchestrator,
                        FinalAnswerComposer finalAnswerComposer,
                        MemoryService memoryService,
                        PromptBuilder promptBuilder) {
        this.chatAgentOrchestrator = chatAgentOrchestrator;
        this.finalAnswerComposer = finalAnswerComposer;
        this.memoryService = memoryService;
        this.promptBuilder = promptBuilder;
    }

    public RoutingOutcome route(IntentDecision decision, ChatRequest request, List<ChatMessage> history) {
        return switch (decision.intent()) {
            case KB, MCP -> RoutingOutcome.agent(chatAgentOrchestrator.prepare(
                    request, history, chatAgentOrchestrator.retrieve(request)));
            case SYSTEM -> RoutingOutcome.direct(buildDirectPrompt(request, history));
            case CLARIFY -> RoutingOutcome.clarify(decision.clarification());
        };
    }

    public RoutingOutcome route(IntentDecision decision,
                                ChatRequest request,
                                List<ChatMessage> history,
                                AgentRuntimeListener listener) {
        return route(decision, request, history, listener, null);
    }

    public RoutingOutcome route(IntentDecision decision,
                                ChatRequest request,
                                List<ChatMessage> history,
                                AgentRuntimeListener listener,
                                AtomicBoolean cancelSignal) {
        return switch (decision.intent()) {
            case KB, MCP -> RoutingOutcome.agent(chatAgentOrchestrator.prepare(
                    request, history, chatAgentOrchestrator.retrieve(request), listener, cancelSignal));
            case SYSTEM -> RoutingOutcome.direct(buildDirectPrompt(request, history));
            case CLARIFY -> RoutingOutcome.clarify(decision.clarification());
        };
    }

    private String buildDirectPrompt(ChatRequest request, List<ChatMessage> history) {
        return promptBuilder.buildPrompt(
                request.getQuestion(),
                history,
                List.of(),
                null,
                null,
                null,
                false
        );
    }

    public record RoutingOutcome(
            Mode mode,
            ChatAgentOrchestrator.ToolOrchestrationResult orchestration,
            String directPrompt,
            String clarification
    ) {
        public static RoutingOutcome agent(ChatAgentOrchestrator.ToolOrchestrationResult orchestration) {
            return new RoutingOutcome(Mode.AGENT, orchestration, null, null);
        }

        public static RoutingOutcome direct(String prompt) {
            return new RoutingOutcome(Mode.DIRECT, null, prompt, null);
        }

        public static RoutingOutcome clarify(String question) {
            return new RoutingOutcome(Mode.CLARIFY, null, null, question);
        }
    }

    public enum Mode {
        AGENT,
        DIRECT,
        CLARIFY
    }
}