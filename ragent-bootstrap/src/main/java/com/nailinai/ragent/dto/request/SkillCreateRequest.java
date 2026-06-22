package com.nailinai.ragent.dto.request;

import lombok.Data;

@Data
public class SkillCreateRequest {

    private String name;
    private String title;
    private String description;
    private String content;
    private String toolName;
    private String executorType;
    private String debugQuestion;
    private String debugArgumentsJson;
    private String debugArgumentsSchemaJson;
    private Boolean overwrite;
}
