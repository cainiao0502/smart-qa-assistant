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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class McpProcessManagerImpl implements McpProcessManager {

    private static final int MAX_LOG_LINES = 60;

    /** stdout 读取线程结束的标记行：用独立常量做等值判断，不会与真实输出撞车。 */
    static final String EOF_MARKER = "__MCP_STDIO_EOF__";

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
                                     String payloadJson,
                                     long timeoutMs) {
        ManagedProcess managed = ensureStarted(serverId, server);
        if (!managed.stdioCapable()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP server is not configured as stdio: " + serverId);
        }

        // 写和等响应都必须持锁：stdio 上同时只允许一个在途请求，响应才能按 id 精确配对。
        // 锁的持有时间上限是 timeoutMs——之前这里是无超时的阻塞读，子进程一卡住，
        // 持锁线程挂死，该 server 的后续所有调用就永远排在锁后面。
        synchronized (managed.lock) {
            try {
                managed.writer.write(payloadJson);
                managed.writer.write('\n');
                managed.writer.flush();
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to communicate with MCP stdio server: " + ex.getMessage());
            }
            try {
                return awaitStdioResponse(managed.stdoutLines, requestId, timeoutMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interrupted while waiting for MCP stdio response");
            }
        }
    }

    /**
     * 从 stdout 行队列里等指定 id 的响应。
     *
     * <p>包私有：队列消费逻辑（id 配对、通知丢弃、超时、进程退出）不依赖真实进程，可直接单测。
     * 超时<b>不杀进程</b>——晚到的响应会因 id 不匹配被下次读取丢弃，server 卡了一次不代表它死了。</p>
     */
    JsonNode awaitStdioResponse(BlockingQueue<String> lines, String requestId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "MCP stdio request timed out after %dms (requestId=%s)".formatted(timeoutMs, requestId));
            }
            String line = lines.poll(Math.min(remaining, 250), TimeUnit.MILLISECONDS);
            if (EOF_MARKER.equals(line)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "MCP stdio process exited before returning a response (requestId=%s)".formatted(requestId));
            }
            if (line == null || !StringUtils.hasText(line)) {
                continue;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (IOException ex) {
                // server 把日志误写进 stdout 的非 JSON 行：跳过，不当成协议消息
                continue;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            JsonNode idNode = node.get("id");
            if (idNode == null || idNode.isNull() || !requestId.equals(idNode.asText())) {
                // server 主动通知、或已超时作废请求的迟到响应：丢弃
                continue;
            }
            JsonNode errorNode = node.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP error: " + errorNode.path("message").asText("unknown MCP error"));
            }
            return node.path("result");
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
                managed.startStdoutReader(serverId);
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
        private final BufferedWriter writer;
        private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        private final Deque<String> logs = new ArrayDeque<>();
        private volatile Integer exitCode;

        private ManagedProcess(Process process, List<String> launchCommand, String workingDirectory, boolean stdio) {
            this.process = process;
            this.startedAt = LocalDateTime.now();
            this.launchCommand = launchCommand == null ? List.of() : List.copyOf(launchCommand);
            this.workingDirectory = workingDirectory;
            this.stdio = stdio;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private boolean isRunning() {
            return process.isAlive();
        }

        private boolean stdioCapable() {
            return stdio;
        }

        private void startStdoutReader(String serverId) {
            Thread readerThread = new Thread(() -> readProtocolLines(process.getInputStream()), "mcp-stdout-" + serverId);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        /**
         * stdout 必须由独立线程持续读入队列：请求线程不能直接 {@code readLine()}——
         * 那是没有超时的阻塞读，子进程卡住时持锁线程和后续所有调用一起挂死。
         */
        private void readProtocolLines(InputStream stream) {
            try (BufferedReader protocolReader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = protocolReader.readLine()) != null) {
                    stdoutLines.put(line);
                }
            } catch (IOException | InterruptedException ignored) {
                // 进程被杀 / 读中断：走 EOF 标记收尾
            } finally {
                try {
                    stdoutLines.put(EOF_MARKER);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
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
