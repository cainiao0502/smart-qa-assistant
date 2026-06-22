package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpServerToolResponse {

    private String exposedName;
    private String remoteName;
    private String description;
}
