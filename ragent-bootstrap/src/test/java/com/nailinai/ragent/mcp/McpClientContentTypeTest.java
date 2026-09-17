package com.nailinai.ragent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.chat.service.McpProcessManager;
import com.nailinai.ragent.config.McpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * tools/call 结果提取的 content 类型分支测试。
 *
 * <p>MCP 规范里 {@code content} 是多类型数组，image / audio 的 payload 是 base64。
 * 这里锁死：二进制内容只出占位说明、绝不把编码灌进模型上下文；未知结构走截断兜底。</p>
 */
class McpClientContentTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpClient client = new McpClient(new McpProperties(), objectMapper, mock(McpProcessManager.class));

    @Test
    @DisplayName("image content 只出占位说明，base64 不进入上下文")
    void imageContent_shouldBeReplacedByPlaceholder() throws Exception {
        String pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        JsonNode result = objectMapper.readTree("""
                {"content": [{"type": "image", "mimeType": "image/png", "data": "%s"}]}
                """.formatted(pngBase64));

        String context = client.extractSupplementalContext(result);

        assertThat(context).contains("图片已省略").contains("image/png");
        assertThat(context).doesNotContain(pngBase64);
        assertThat(context).doesNotContain("iVBORw0KGgo");
    }

    @Test
    @DisplayName("text content 原样进入上下文")
    void textContent_shouldPassThrough() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"content": [{"type": "text", "text": "查询结果共 3 条"}]}
                """);

        assertThat(client.extractSupplementalContext(result)).contains("查询结果共 3 条");
    }

    @Test
    @DisplayName("不带 type 但带 text 的旧式 server 兼容")
    void legacyTextWithoutType_shouldPassThrough() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"content": [{"text": "legacy text"}]}
                """);

        assertThat(client.extractSupplementalContext(result)).contains("legacy text");
    }

    @Test
    @DisplayName("resource_link 输出 URI 而非整体序列化")
    void resourceLink_shouldRenderUri() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"content": [{"type": "resource_link", "uri": "file:///project/src/main.rs", "name": "main.rs"}]}
                """);

        String context = client.extractSupplementalContext(result);
        assertThat(context).contains("file:///project/src/main.rs").contains("main.rs");
    }

    @Test
    @DisplayName("内嵌 resource 的 blob 只出占位说明")
    void embeddedResourceBlob_shouldBeReplacedByPlaceholder() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"content": [{"type": "resource", "resource": {"uri": "mem://x", "mimeType": "application/octet-stream", "blob": "QUJDREVGRw=="}}]}
                """);

        String context = client.extractSupplementalContext(result);
        assertThat(context).contains("已省略").doesNotContain("QUJDREVGRw==");
    }

    @Test
    @DisplayName("未知结构超长时截断兜底，不会无限撑大上下文")
    void unknownObject_shouldBeTruncated() throws Exception {
        String longText = "x".repeat(5000);
        JsonNode result = objectMapper.readTree("""
                {"content": [{"type": "weird", "payload": "%s"}]}
                """.formatted(longText));

        String context = client.extractSupplementalContext(result);

        assertThat(context).contains("(truncated)");
        assertThat(context.length()).isLessThan(1200);
    }

    @Test
    @DisplayName("暴露名净化：带点前缀折叠为连字符，满足 OpenAI function name 规范")
    void exposedName_shouldBeSanitizedToOpenAiSpec() {
        McpProperties.ServerProperties server = new McpProperties.ServerProperties();
        server.setToolNamePrefix("tst");
        assertThat(client.toExposedName(server, "read_ledger")).isEqualTo("tst-read_ledger");
        // 任意非法字符（如 : / 空格）都折叠为 -，不出现连续 -，且最长 64
        assertThat(client.toExposedName(server, "a:b/c d")).isEqualTo("tst-a-b-c-d");
    }

    @Test
    @DisplayName("暴露名净化：超长截断到 64 字符")
    void exposedName_shouldTruncateTo64Chars() {
        McpProperties.ServerProperties server = new McpProperties.ServerProperties();
        server.setToolNamePrefix("tst");
        String longName = client.toExposedName(server, "x".repeat(100));
        assertThat(longName.length()).isLessThanOrEqualTo(64);
        assertThat(longName).matches("^[a-zA-Z0-9_-]+$");
    }

    @Test
    @DisplayName("多类型混合：文本保留、二进制占位，其余类型不受影响")
    void mixedContent_shouldBeHandledPerType() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"content": [
                    {"type": "text", "text": "第一段"},
                    {"type": "image", "mimeType": "image/jpeg", "data": "AAAA"},
                    {"type": "text", "text": "第二段"}
                ]}
                """);

        List<String> expectations = List.of("第一段", "第二段", "图片已省略");
        String context = client.extractSupplementalContext(result);
        expectations.forEach(part -> assertThat(context).contains(part));
        assertThat(context).doesNotContain("AAAA");
    }
}
