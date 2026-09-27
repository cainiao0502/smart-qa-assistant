package com.nailinai.ragent.infra.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式换行保留的回归测试。
 *
 * <p>真机缺陷：流式分片边界常恰好切在换行符上，旧实现对每个 delta 做 strip()，
 * 纯换行的分片（"\n\n"）变空被丢弃——整篇回答退化为单行字墙，Markdown 全灭。
 * 本测试锁死「换行 delta 必须转发」的行为。</p>
 */
class StreamNewlinePreservationTest {

    private OpenAICompatibleChatClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAICompatibleChatClient(
                "test", "https://example.invalid/v1", "sk-key", "test-model",
                1, 0, 10_000, new ObjectMapper());
    }

    @Test
    @DisplayName("纯换行的流式分片不被 strip 丢弃")
    void parseStreamContent_shouldPreserveNewlineDelta() {
        var delta = client.parseStreamContent(
                "{\"choices\":[{\"delta\":{\"content\":\"\\n\\n\"}}]}",
                new StringBuilder(), new StringBuilder());

        assertThat(delta.content()).isNotNull().isEqualTo("\n\n");
    }

    @Test
    @DisplayName("consumeStream 端到端：跨分片的换行完整保留在回调序列中")
    void consumeStream_shouldForwardNewlinesAcrossChunkBoundaries() throws Exception {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"第一段内容\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\n\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"## 标题\"}}]}",
                "",
                "data: [DONE]",
                "");
        InputStream in = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));

        List<String> deltas = new ArrayList<>();
        client.consumeStream(in, new StreamCallback() {
            @Override
            public void onReasoning(String delta) {
            }

            @Override
            public void onContent(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable ex) {
                throw new AssertionError("stream should not fail", ex);
            }
        });

        assertThat(deltas).containsExactly("第一段内容", "\n\n", "## 标题");
        assertThat(String.join("", deltas)).isEqualTo("第一段内容\n\n## 标题");
    }
}
