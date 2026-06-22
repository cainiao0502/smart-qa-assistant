package com.nailinai.ragent.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.SessionSummaryResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(chatService.chat(request));
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            Map<String, Object> startPayload = new LinkedHashMap<>();
            startPayload.put("sessionId", request.getSessionId());
            startPayload.put("kbId", request.getKbId());
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data(startPayload));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            return ResponseEntity.internalServerError().body(emitter);
        }
        CompletableFuture.runAsync(() -> chatService.streamChat(request, emitter));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessage>> getSessionMessages(@PathVariable String sessionId) {
        return Result.success(chatService.getSessionMessages(sessionId));
    }

    @GetMapping("/runs/{runId}")
    public Result<AgentRunDetailResponse> getRunDetail(@PathVariable String runId) {
        return Result.success(chatService.getRunDetail(runId));
    }

    @GetMapping("/sessions")
    public Result<List<SessionSummaryResponse>> listSessions() {
        return Result.success(chatService.listSessions());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success(null);
    }
}
