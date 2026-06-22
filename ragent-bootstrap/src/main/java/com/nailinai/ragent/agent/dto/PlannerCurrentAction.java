package com.nailinai.ragent.agent.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PlannerCurrentAction {

    private String taskKey;
    private String action;
    private String tool;
    private Map<String, Object> arguments;
    private String response;
    private String reason;
}
