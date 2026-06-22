package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReferenceChunkResponse {

    private Long docId;
    private String documentName;
    private String fileType;
    private Integer chunkIndex;
    private Integer paragraphIndex;
    private String chunkText;
    private Double score;
    private Double distance;
    private Double rerankScore;
    private String hitReason;
}
