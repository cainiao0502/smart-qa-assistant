package com.nailinai.ragent.chat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.framework.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * stdio 响应队列消费逻辑的单测。
 *
 * <p>修复前的实现是「持锁同步 readLine」：没有超时，子进程卡住时该 server 的
 * 全部后续调用跟着挂死。现在 stdout 由独立线程进队列，这里锁住队列消费的四个行为：
 * id 配对、通知/迟到响应丢弃、超时报错、进程退出报错——都不需要真实进程。</p>
 */
class McpStdioResponsePollTest {

    private final McpProcessManagerImpl manager = new McpProcessManagerImpl(new ObjectMapper());
    private static final long TIMEOUT_MS = 500;

    @Test
    @DisplayName("id 匹配的响应：返回 result")
    void shouldReturnResultForMatchingId() throws InterruptedException {
        BlockingQueue<String> lines = queue(
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"tools\":[]}}");

        JsonNode result = manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS);

        assertThat(result.toString()).contains("tools");
    }

    @Test
    @DisplayName("通知与 id 不匹配的迟到响应：丢弃并继续等待")
    void shouldSkipNotificationsAndStaleResponses() throws InterruptedException {
        BlockingQueue<String> lines = queue(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"stale-req\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"ok\":true}}");

        JsonNode result = manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS);

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("响应携带 error：抛出服务端错误信息")
    void shouldSurfaceServerError() {
        BlockingQueue<String> lines = queue(
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}");

        assertThatThrownBy(() -> manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid params");
    }

    @Test
    @DisplayName("响应超时：按毫秒上限抛错，而不是永久挂死")
    void shouldThrowOnTimeout() {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        assertThatThrownBy(() -> manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("读到进程退出标记：报进程退出而不是超时")
    void shouldReportExitOnEofMarker() {
        BlockingQueue<String> lines = queue(McpProcessManagerImpl.EOF_MARKER);

        assertThatThrownBy(() -> manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exited before returning");
    }

    @Test
    @DisplayName("混入 stdout 的非 JSON 行：跳过，不当成协议消息")
    void shouldSkipNonJsonLines() throws InterruptedException {
        BlockingQueue<String> lines = queue(
                "[server] listening on stdio",
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"ok\":1}}");

        JsonNode result = manager.awaitStdioResponse(lines, "req-1", TIMEOUT_MS);

        assertThat(result.path("ok").asInt()).isEqualTo(1);
    }

    private static BlockingQueue<String> queue(String... values) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        for (String value : values) {
            lines.add(value);
        }
        return lines;
    }
}
