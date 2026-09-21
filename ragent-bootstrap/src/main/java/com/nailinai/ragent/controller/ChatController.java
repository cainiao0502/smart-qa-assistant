package com.nailinai.ragent.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.framework.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.nailinai.ragent.dto.request.ApprovalDecisionRequest;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.AgentRunDetailResponse;
import com.nailinai.ragent.dto.response.ChatResponse;
import com.nailinai.ragent.dto.response.SessionSummaryResponse;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.chat.service.ChatService;
import com.nailinai.ragent.user.context.UserIdHolder;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(chatService.chat(request));
    }

    /**
     * SSE 长连接的总超时：一把保护伞。历史实现用 0L（永不超时），一旦异步链路里
     * 出现任何漏发 complete 的路径，连接和资源会永久挂住。10 分钟足够覆盖最长的
     * 多步 agent 运行；到点由 Spring 强制 complete，避免泄漏。
     */
    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * SSE 专用执行器。历史实现用 CompletableFuture.runAsync 默认的
     * ForkJoinPool.commonPool，而流式链路里审批等待最长可阻塞 120 秒——
     * 少量并发会话即可占满 commonPool，拖累 JVM 内所有依赖它的任务。
     * 虚拟线程逐任务开销极小，阻塞等待不占用平台线程。
     */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
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
        // 必须在请求线程内捕获用户身份：下面提交的异步任务运行在独立线程池中，
        // 那里读不到 Sa-Token 的 ThreadLocal，会导致 owner_user_id 落库为 NULL、
        // 历史会话列表因此恒为空（详见 UserIdHolder）。
        Long currentUserId = UserIdHolder.capture();
        streamExecutor.execute(() -> {
            UserIdHolder.set(currentUserId);
            try {
                chatService.streamChat(request, emitter);
            } catch (Throwable unexpected) {
                // 兜底：服务层任何未处理异常都必须终止 emitter，否则连接永久挂起
                log.error("stream chat failed unexpectedly, sessionId={}", request.getSessionId(), unexpected);
                emitter.completeWithError(unexpected);
            } finally {
                UserIdHolder.clear();
            }
        });
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

    /**
     * 提交工具审批决定。
     *
     * <p>为什么是独立的 HTTP 请求而不是走 SSE：SSE 是单向通道，客户端无法在
     * 同一条连接上回话。Agent 线程此时正阻塞等待结论，本接口负责把结论送回会合点。</p>
     *
     * <p>「不属于当前用户」与「已过期」返回同样的结果，避免用响应差异探测
     * 他人审批标识是否存在。</p>
     */
    @PostMapping("/approvals/{approvalId}")
    public Result<Void> decideApproval(@PathVariable String approvalId,
                                       @Valid @RequestBody ApprovalDecisionRequest request) {
        boolean accepted = chatService.decideApproval(approvalId, Boolean.TRUE.equals(request.getApproved()));
        if (!accepted) {
            return Result.failure(ErrorCode.NOT_FOUND, "approval request not found or already settled");
        }
        return Result.success(null);
    }
}
