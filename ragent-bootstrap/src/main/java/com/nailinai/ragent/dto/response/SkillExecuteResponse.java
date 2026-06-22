package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SkillExecuteResponse {

    private String skillName;
    private String question;
    private String summary;
    private String output;
    private ToolCallTraceResponse trace;
}
