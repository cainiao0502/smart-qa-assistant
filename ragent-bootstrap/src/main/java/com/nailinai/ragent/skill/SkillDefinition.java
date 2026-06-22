package com.nailinai.ragent.skill;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SkillDefinition {

    private String name;
    private String title;
    private String description;
    private String content;
    private String sourcePath;
    private String toolName;
    private String executorType;
    private String debugQuestion;
    private String debugArgumentsJson;
    private String debugArgumentsSchemaJson;
    private boolean executable;
}
