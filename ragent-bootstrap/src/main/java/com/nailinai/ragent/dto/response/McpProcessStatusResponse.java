package com.nailinai.ragent.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class McpProcessStatusResponse {

    private boolean launchConfigured;
    private boolean running;
    private Long pid;
    private LocalDateTime startedAt;
    private Integer exitCode;
    private String workingDirectory;
    private List<String> launchCommand;
    private List<String> recentLogs;
}
