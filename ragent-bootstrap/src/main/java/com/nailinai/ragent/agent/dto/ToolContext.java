package com.nailinai.ragent.agent.dto;

import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.entity.ChatMessage;
import com.nailinai.ragent.entity.DocumentChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ToolContext {

    private ChatRequest request;
    private List<ChatMessage> history;
    private List<DocumentChunk> retrievedChunks;
}
