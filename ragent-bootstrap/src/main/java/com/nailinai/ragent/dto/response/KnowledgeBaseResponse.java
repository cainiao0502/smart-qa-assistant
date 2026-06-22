package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseResponse {

    private Long id;
    private String name;
    private String description;
    private LocalDateTime createdAt;
}
