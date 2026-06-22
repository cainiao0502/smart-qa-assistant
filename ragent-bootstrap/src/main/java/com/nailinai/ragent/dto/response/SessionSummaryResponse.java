package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionSummaryResponse {

    private String sessionId;
    private String title;
    private String preview;
    private Long kbId;
    private String kbName;
    private int messageCount;
    private String lastActivityAt;
}
