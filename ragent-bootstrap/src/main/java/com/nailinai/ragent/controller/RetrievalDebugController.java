package com.nailinai.ragent.controller;

import com.nailinai.ragent.chat.service.RetrievalService;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.framework.common.Result;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试端点：返回原始检索结果（通道得分、重排得分、命中原因），
 * 用于离线评估与消融实验（recall@k / MRR 等指标的计算数据源）。
 *
 * <p>仅管理员可用（见 AsyncSafeSaInterceptor 路由规则）——该端点暴露检索内部
 * 得分细节，且评估脚本会绕过业务阈值，不适合对普通用户开放。
 */
@RestController
@RequestMapping("/api/retrieval/debug")
public class RetrievalDebugController {

    private final RetrievalService retrievalService;

    public RetrievalDebugController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public Result<Map<String, Object>> search(@RequestBody DebugSearchRequest request) {
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setKbId(request.getKbId());
        chatRequest.setQuestion(request.getQuestion());
        chatRequest.setTopK(request.getTopK() == null ? 8 : request.getTopK());
        // 评估场景不希望业务阈值截断候选集，否则指标会被阈值混淆；由调用方自行决定是否过滤
        chatRequest.setScoreThreshold(0.0);

        RetrievalResult result = retrievalService.retrieve(chatRequest, 8, 0.0);

        List<Map<String, Object>> chunks = result.getChunks().stream()
                .map(this::toChunkView)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalQuery", result.getOriginalQuery());
        body.put("effectiveQuery", result.getEffectiveQuery());
        body.put("queryRewritten", result.isQueryRewritten());
        body.put("reranked", result.isReranked());
        body.put("chunks", chunks);
        return Result.success(body);
    }

    private Map<String, Object> toChunkView(DocumentChunk chunk) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("chunkIndex", chunk.getChunkIndex());
        view.put("docId", chunk.getDocId());
        view.put("documentName", chunk.getDocumentName());
        view.put("score", chunk.getScore());
        view.put("rerankScore", chunk.getRerankScore());
        view.put("hitReason", chunk.getHitReason());
        view.put("preview", chunk.getChunkText() == null ? null
                : chunk.getChunkText().substring(0, Math.min(120, chunk.getChunkText().length())));
        return view;
    }

    @Data
    public static class DebugSearchRequest {
        private Long kbId;
        private String question;
        private Integer topK;
    }
}
