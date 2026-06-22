package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.dto.request.CreateKnowledgeBaseRequest;
import com.nailinai.ragent.dto.response.KnowledgeBaseResponse;

import java.util.List;

public interface KnowledgeBaseService {

    KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request);

    List<KnowledgeBaseResponse> list();

    void delete(Long kbId);
}
