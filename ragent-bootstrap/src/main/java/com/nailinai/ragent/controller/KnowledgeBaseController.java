package com.nailinai.ragent.controller;

import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.request.CreateKnowledgeBaseRequest;
import com.nailinai.ragent.dto.response.KnowledgeBaseResponse;
import com.nailinai.ragent.chat.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return Result.success(knowledgeBaseService.create(request));
    }

    @GetMapping
    public Result<List<KnowledgeBaseResponse>> list() {
        return Result.success(knowledgeBaseService.list());
    }

    @DeleteMapping("/{kbId}")
    public Result<Void> delete(@org.springframework.web.bind.annotation.PathVariable Long kbId) {
        knowledgeBaseService.delete(kbId);
        return Result.success();
    }
}
