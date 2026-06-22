package com.nailinai.ragent.controller;

import com.nailinai.ragent.dto.request.McpServerUpsertRequest;
import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.response.McpOverviewResponse;
import com.nailinai.ragent.dto.response.McpServerStatusResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.nailinai.ragent.chat.service.McpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping("/servers")
    public Result<McpOverviewResponse> listServers() {
        return Result.success(mcpService.overview());
    }

    @PostMapping("/servers")
    public Result<McpServerStatusResponse> createServer(@Valid @RequestBody McpServerUpsertRequest request) {
        return Result.success(mcpService.createServer(request));
    }

    @PutMapping("/servers/{serverId}")
    public Result<McpServerStatusResponse> updateServer(@PathVariable String serverId,
                                                        @Valid @RequestBody McpServerUpsertRequest request) {
        return Result.success(mcpService.updateServer(serverId, request));
    }

    @DeleteMapping("/servers/{serverId}")
    public Result<Void> deleteServer(@PathVariable String serverId) {
        mcpService.deleteServer(serverId);
        return Result.success();
    }

    @PostMapping("/servers/{serverId}/start")
    public Result<McpServerStatusResponse> startServer(@PathVariable String serverId) {
        return Result.success(mcpService.startServer(serverId));
    }

    @PostMapping("/servers/{serverId}/stop")
    public Result<McpServerStatusResponse> stopServer(@PathVariable String serverId) {
        return Result.success(mcpService.stopServer(serverId));
    }
}
