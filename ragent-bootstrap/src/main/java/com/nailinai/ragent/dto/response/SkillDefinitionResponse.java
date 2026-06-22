package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SkillDefinitionResponse {

    private String name;
    private String title;
    private String description;
    private String toolName;
    private String executorType;
    private boolean executable;
}
