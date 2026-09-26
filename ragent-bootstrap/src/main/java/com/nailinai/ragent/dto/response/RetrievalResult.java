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
    /** 本轮检索中因异常降级退出的通道名（空列表 = 全部通道正常参与） */
    private List<String> degradedChannels;
    private List<DocumentChunk> chunks;
}
