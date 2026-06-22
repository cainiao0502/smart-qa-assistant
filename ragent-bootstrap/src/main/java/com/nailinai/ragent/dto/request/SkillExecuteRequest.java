package com.nailinai.ragent.dto.request;

import lombok.Data;

import java.util.Map;

@Data
public class SkillExecuteRequest {

    private String question;
    private Map<String, Object> arguments;
}
