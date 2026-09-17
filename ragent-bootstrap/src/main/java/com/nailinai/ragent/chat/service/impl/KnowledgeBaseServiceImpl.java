package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.CreateKnowledgeBaseRequest;
import com.nailinai.ragent.dto.response.KnowledgeBaseResponse;
import com.nailinai.ragent.entity.KnowledgeBase;
import com.nailinai.ragent.mapper.AgentRunMapper;
import com.nailinai.ragent.mapper.AgentStepMapper;
import com.nailinai.ragent.mapper.ChatMessageMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.chat.service.KnowledgeBaseService;
import com.nailinai.ragent.user.context.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentStepMapper agentStepMapper;
    private final AgentRunMapper agentRunMapper;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    DocumentMapper documentMapper,
                                    DocumentService documentService,
                                    ChatMessageMapper chatMessageMapper,
                                    AgentStepMapper agentStepMapper,
                                    AgentRunMapper agentRunMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.documentService = documentService;
        this.chatMessageMapper = chatMessageMapper;
        this.agentStepMapper = agentStepMapper;
        this.agentRunMapper = agentRunMapper;
    }

    @Override
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        Long userId = UserContext.currentUserId();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setOwnerUserId(userId);
        knowledgeBaseMapper.insert(knowledgeBase);
        return toResponse(knowledgeBase);
    }

    @Override
    public List<KnowledgeBaseResponse> list() {
        Long userId = UserContext.currentUserId();
        return knowledgeBaseMapper.selectByOwner(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long kbId) {
        Long userId = UserContext.currentUserId();
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectByIdAndOwner(kbId, userId);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }

        documentMapper.selectByKbId(kbId)
                .forEach(document -> documentService.delete(document.getId()));
        chatMessageMapper.deleteByKbId(kbId);
        agentStepMapper.deleteByKbId(kbId);
        agentRunMapper.deleteByKbId(kbId);
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
