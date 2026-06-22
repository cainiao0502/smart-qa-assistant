package com.nailinai.ragent.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateKnowledgeBaseRequest {

    @NotBlank(message = "knowledge base name is required")
    private String name;

    private String description;
}
