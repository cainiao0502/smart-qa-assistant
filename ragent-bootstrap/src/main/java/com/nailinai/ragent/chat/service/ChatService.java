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

    /**
     * 提交一次工具审批决定（仅发起该次运行的用户可提交）。
     *
     * @return 是否命中了一个仍在等待、且属于当前用户的审批
     */
    boolean decideApproval(String approvalId, boolean approved);
}
