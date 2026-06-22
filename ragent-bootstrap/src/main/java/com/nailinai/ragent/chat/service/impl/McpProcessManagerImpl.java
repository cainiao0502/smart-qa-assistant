package com.nailinai.ragent.chat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.config.McpProperties;
import com.nailinai.ragent.dto.response.McpProcessStatusResponse;
import com.nailinai.ragent.chat.service.McpProcessManager;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class McpProcessManagerImpl implements McpProcessManager {

    private static final int MAX_LOG_LINES = 60;

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public McpProcessManagerImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public McpProcessStatusResponse getStatus(String serverId, McpProperties.ServerProperties server) {
        ManagedProcess managed = processes.get(serverId);
        return toResponse(managed, server);
    }

    @Override
    public McpProcessStatusResponse start(String serverId, McpProperties.ServerProperties server) {
        ManagedProcess managed = ensureStarted(serverId, server);
        return toResponse(managed, server);
    }

    @Override
    public McpProcessStatusResponse stop(String serverId) {
        ManagedProcess managed = processes.get(serverId);
        if (managed == null) {
            return toResponse(null, null);
        }

        synchronized (managed.lock) {
            if (managed.isRunning()) {
                // Destroy entire process tree (important on Windows for npx/node child processes)
                managed.process.descendants().forEach(ProcessHandle::destroyForcibly);
                managed.process.destroyForcibly();
                try {
                    managed.process.waitFor();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interrupted while stopping MCP server");
                }
            }
            managed.markExited();
        }
        processes.remove(serverId);
        return toResponse(managed, null);
    }

    @Override
    public JsonNode sendStdioRequest(String serverId,
                                     McpProperties.ServerProperties server,
                                     String requestId,
                                     String payloadJson) {
        ManagedProcess managed = ensureStarted(serverId, server);
        if (!managed.stdioCapable()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP server is not configured as stdio: " + serverId);
        }

        synchronized (managed.lock) {
            try {
                managed.writer.write(payloadJson);
                managed.writer.write('\n');
                managed.writer.flush();

                String line;
                while ((line = managed.reader.readLine()) != null) {
                    if (!StringUtils.hasText(line)) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && requestId.equals(idNode.asText())) {
                        JsonNode errorNode = node.path("error");
                        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                            String errorMessage = errorNode.path("message").asText("unknown MCP error");
                            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP error: " + errorMessage);
                        }
                        return node.path("result");
                    }
                }
                managed.markExited();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP stdio process exited before returning a response");
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to communicate with MCP stdio server: " + ex.getMessage());
            }
        }
    }

    @Override
    public void sendStdioNotification(String serverId,
                                      McpProperties.ServerProperties server,
                                      String payloadJson) {
        ManagedProcess managed = ensureStarted(serverId, server);
        if (!managed.stdioCapable()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP server is not configured as stdio: " + serverId);
        }

        synchronized (managed.lock) {
            try {
                managed.writer.write(payloadJson);
                managed.writer.write('\n');
                managed.writer.flush();
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to send MCP stdio notification: " + ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdownAll() {
        for (ManagedProcess managed : processes.values()) {
            if (managed.isRunning()) {
                managed.process.destroy();
            }
        }
    }

    private ManagedProcess ensureStarted(String serverId, McpProperties.ServerProperties server) {
        validateLaunchable(serverId, server);

        ManagedProcess existing = processes.get(serverId);
        if (existing != null && existing.isRunning()) {
            return existing;
        }

        synchronized (processes) {
            ManagedProcess latest = processes.get(serverId);
            if (latest != null && latest.isRunning()) {
                return latest;
            }

            try {
                ProcessBuilder builder = new ProcessBuilder(resolveLaunchCommand(server.getLaunchCommand()));
                builder.redirectErrorStream(false);

                if (StringUtils.hasText(server.getWorkingDirectory())) {
                    builder.directory(new File(server.getWorkingDirectory()));
                }
                if (server.getEnvironment() != null && !server.getEnvironment().isEmpty()) {
                    builder.environment().putAll(server.getEnvironment());
                }

                Process process = builder.start();
                ManagedProcess managed = new ManagedProcess(process, server.getLaunchCommand(), server.getWorkingDirectory(), server.isStdio());
                processes.put(serverId, managed);
                managed.startStderrReader(serverId);
                process.onExit().thenRun(managed::markExited);
                return managed;
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to start MCP server: " + ex.getMessage());
            }
        }
    }

    private void validateLaunchable(String serverId, McpProperties.ServerProperties server) {
        if (server == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP server not found: " + serverId);
        }
        if (server.getLaunchCommand() == null || server.getLaunchCommand().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Launch command is not configured for MCP server: " + serverId);
        }
    }

    private McpProcessStatusResponse toResponse(ManagedProcess managed, McpProperties.ServerProperties server) {
        boolean launchConfigured = server != null && server.getLaunchCommand() != null && !server.getLaunchCommand().isEmpty();
        if (managed == null) {
            return McpProcessStatusResponse.builder()
                    .launchConfigured(launchConfigured)
                    .running(false)
                    .pid(null)
                    .startedAt(null)
                    .exitCode(null)
                    .workingDirectory(server == null ? null : server.getWorkingDirectory())
                    .launchCommand(server == null ? List.of() : safeCopy(server.getLaunchCommand()))
                    .recentLogs(List.of())
                    .build();
        }

        return McpProcessStatusResponse.builder()
                .launchConfigured(launchConfigured || !managed.launchCommand.isEmpty())
                .running(managed.isRunning())
                .pid(managed.process.pid())
                .startedAt(managed.startedAt)
                .exitCode(managed.exitCode)
                .workingDirectory(StringUtils.hasText(managed.workingDirectory) ? managed.workingDirectory : server == null ? null : server.getWorkingDirectory())
                .launchCommand(!managed.launchCommand.isEmpty() ? List.copyOf(managed.launchCommand) : server == null ? List.of() : safeCopy(server.getLaunchCommand()))
                .recentLogs(managed.snapshotLogs())
                .build();
    }

    private List<String> safeCopy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<String> resolveLaunchCommand(List<String> rawCommand) {
        if (rawCommand == null || rawCommand.isEmpty()) {
            return List.of();
        }

        List<String> resolved = new ArrayList<>(rawCommand);
        resolved.set(0, resolveExecutable(rawCommand.get(0)));
        return resolved;
    }

    private String resolveExecutable(String executable) {
        if (!isWindows() || !StringUtils.hasText(executable)) {
            return executable;
        }

        String trimmed = executable.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat") || lower.endsWith(".ps1")) {
            return trimmed;
        }

        if ("npx".equalsIgnoreCase(trimmed) || "npm".equalsIgnoreCase(trimmed)) {
            return trimmed + ".cmd";
        }

        return trimmed;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class ManagedProcess {
        private final Object lock = new Object();
        private final Process process;
        private final LocalDateTime startedAt;
        private final List<String> launchCommand;
        private final String workingDirectory;
        private final boolean stdio;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final Deque<String> logs = new ArrayDeque<>();
        private volatile Integer exitCode;

        private ManagedProcess(Process process, List<String> launchCommand, String workingDirectory, boolean stdio) {
            this.process = process;
            this.startedAt = LocalDateTime.now();
            this.launchCommand = launchCommand == null ? List.of() : List.copyOf(launchCommand);
            this.workingDirectory = workingDirectory;
            this.stdio = stdio;
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private boolean isRunning() {
            return process.isAlive();
        }

        private boolean stdioCapable() {
            return stdio;
        }

        private void startStderrReader(String serverId) {
            Thread readerThread = new Thread(() -> readLogs(process.getErrorStream()), "mcp-stderr-" + serverId);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLogs(InputStream stream) {
            try (BufferedReader logReader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = logReader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ex) {
                appendLog("[stderr-reader-error] " + ex.getMessage());
            }
        }

        private synchronized void appendLog(String line) {
            if (!StringUtils.hasText(line)) {
                return;
            }
            if (logs.size() >= MAX_LOG_LINES) {
                logs.removeFirst();
            }
            logs.addLast(line);
        }

        private synchronized List<String> snapshotLogs() {
            return new ArrayList<>(logs);
        }

        private void markExited() {
            if (exitCode == null) {
                try {
                    exitCode = process.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    exitCode = null;
                }
            }
        }
    }
}
