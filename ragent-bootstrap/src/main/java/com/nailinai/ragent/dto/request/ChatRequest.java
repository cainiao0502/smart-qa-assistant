package com.nailinai.ragent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    private Long kbId;

    @NotBlank(message = "sessionId is required")
    private String sessionId;

    @NotBlank(message = "question is required")
    private String question;

    @Min(value = 1, message = "topK must be greater than 0")
    private Integer topK;

    @DecimalMin(value = "0.0", message = "scoreThreshold must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "scoreThreshold must be between 0 and 1")
    private Double scoreThreshold;

    private List<Long> documentIds;

    private List<String> fileTypes;

    private String documentNameKeyword;

    private List<String> skillNames;

    private Boolean agentEnabled;
}
