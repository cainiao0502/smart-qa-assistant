package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.entity.DocumentChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetrievalResult {

    private String originalQuery;
    private String effectiveQuery;
    private boolean queryRewritten;
    private boolean reranked;
    private List<DocumentChunk> chunks;
}
