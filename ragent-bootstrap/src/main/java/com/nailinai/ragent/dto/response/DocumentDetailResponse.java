package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.enums.DocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DocumentDetailResponse {

    private Long id;
    private Long kbId;
    private String name;
    private String fileType;
    private DocumentStatus status;
    private Long chunkCount;
    private LocalDateTime createdAt;
    private String content;
    private List<ChunkItem> chunks;

    @Data
    @Builder
    public static class ChunkItem {
        private Integer chunkIndex;
        private String chunkText;
        private Integer tokenEstimate;
        private Integer paragraphIndex;
    }
}
