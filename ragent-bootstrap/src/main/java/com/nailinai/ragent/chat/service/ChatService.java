package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.dto.response.SessionSummaryResponse;
import com.nailinai.ragent.entity.ChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {

    ChatResponse chat(ChatRequest request);

    void streamChat(ChatRequest request, SseEmitter emitter);

    List<ChatMessage> getSessionMessages(String sessionId);

    List<SessionSummaryResponse> listSessions();

    void deleteSession(String sessionId);

    AgentRunDetailResponse getRunDetail(String runId);
}
