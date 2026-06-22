package com.nailinai.ragent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentChunk {

    private Long id;
    private Long kbId;
    private Long docId;
    private String documentName;
    private String fileType;
    private Integer chunkIndex;
    private String chunkText;
    private Integer tokenEstimate;
    private String embeddingLiteral;
    private Integer paragraphIndex;
    private Double score;
    private Double distance;
    private Double rerankScore;
    private String hitReason;
    private LocalDateTime createdAt;
}
