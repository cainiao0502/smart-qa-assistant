package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.CreateKnowledgeBaseRequest;
import com.nailinai.ragent.dto.response.KnowledgeBaseResponse;
import com.nailinai.ragent.entity.KnowledgeBase;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.chat.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final ChatMessageMapper chatMessageMapper;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    DocumentMapper documentMapper,
                                    DocumentService documentService,
                                    ChatMessageMapper chatMessageMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.documentService = documentService;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBaseMapper.insert(knowledgeBase);
        return toResponse(knowledgeBase);
    }

    @Override
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseMapper.selectAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }

        documentMapper.selectByKbId(kbId)
                .forEach(document -> documentService.delete(document.getId()));
        chatMessageMapper.deleteByKbId(kbId);
        knowledgeBaseMapper.deleteById(kbId);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return KnowledgeBaseResponse.builder()
                .id(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .description(knowledgeBase.getDescription())
                .createdAt(knowledgeBase.getCreatedAt())
                .build();
    }
}
