package com.nailinai.ragent.dto.response;

import com.nailinai.ragent.enums.DocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentResponse {

    private Long id;
    private Long kbId;
    private String name;
    private String fileType;
    private DocumentStatus status;
    private Long chunkCount;
    private LocalDateTime createdAt;
}
