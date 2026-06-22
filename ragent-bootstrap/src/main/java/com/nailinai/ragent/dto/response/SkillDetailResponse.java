package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SkillDetailResponse {

    private String name;
    private String title;
    private String description;
    private String sourcePath;
    private String content;
    private String toolName;
    private String executorType;
    private String debugQuestion;
    private String debugArgumentsJson;
    private String debugArgumentsSchemaJson;
    private boolean executable;
}
