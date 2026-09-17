package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.infra.vision.VisionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImageAwareDocumentParser 的图片识别与降级测试。
 *
 * <p>重点覆盖「失败也不能让文档入库整体失败」这一原则：
 * 未配置视觉模型、视觉模型报错、模型返回空描述，都必须退化为纯文本解析。
 */
class ImageAwareDocumentParserTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("非图片文件：直接委托纯文本解析，不触碰视觉模型")
    void nonImageFile_shouldDelegateToTextParser() throws IOException {
        Path file = writeText("notes.txt", "hello");
        TikaDocumentParser textParser = mock(TikaDocumentParser.class);
        when(textParser.parse(any())).thenReturn("hello");
        VisionClient visionClient = mock(VisionClient.class);

        ImageAwareDocumentParser parser = new ImageAwareDocumentParser(textParser, visionClient);

        assertThat(parser.parse(file.toFile())).isEqualTo("hello");
        verify(visionClient, never()).describe(any(), any(), any());
    }

    @Test
    @DisplayName("图片文件但未配置视觉模型：退化为纯文本解析，不调用视觉接口")
    void imageWithoutVision_shouldFallBackToTextParser() throws IOException {
        Path file = writePng("shot.png");
        TikaDocumentParser textParser = mock(TikaDocumentParser.class);
        when(textParser.parse(any())).thenReturn("");

        ImageAwareDocumentParser parser = new ImageAwareDocumentParser(textParser, VisionClient.unavailable());

        assertThat(parser.parse(file.toFile())).isEmpty();
        verify(textParser).parse(any());
    }

    @Test
    @DisplayName("图片文件且视觉模型可用：返回带来源标记的描述文本")
    void imageWithVision_shouldReturnDescription() throws IOException {
        Path file = writePng("chart.png");
        TikaDocumentParser textParser = mock(TikaDocumentParser.class);
        VisionClient visionClient = mock(VisionClient.class);
        when(visionClient.available()).thenReturn(true);
        when(visionClient.describe(any(), any(), any())).thenReturn("一张柱状图，横轴是月份，纵轴是访问量。");

        ImageAwareDocumentParser parser = new ImageAwareDocumentParser(textParser, visionClient);

        String result = parser.parse(file.toFile());

        assertThat(result).startsWith("[图片内容描述]").contains("柱状图");
        verify(textParser, never()).parse(any());
    }

    @Test
    @DisplayName("视觉模型调用失败：退化为纯文本解析，而不是让文档入库失败")
    void visionFailure_shouldFallBackToTextParser() throws IOException {
        Path file = writePng("broken.png");
        TikaDocumentParser textParser = mock(TikaDocumentParser.class);
        when(textParser.parse(any())).thenReturn("fallback");
        VisionClient visionClient = mock(VisionClient.class);
        when(visionClient.available()).thenReturn(true);
        when(visionClient.describe(any(), any(), any()))
                .thenThrow(new IllegalStateException("vision api down"));

        ImageAwareDocumentParser parser = new ImageAwareDocumentParser(textParser, visionClient);

        assertThat(parser.parse(file.toFile())).isEqualTo("fallback");
    }

    @Test
    @DisplayName("视觉模型返回空描述：不产出「[图片内容描述]」空壳文本")
    void emptyDescription_shouldReturnEmptyString() throws IOException {
        Path file = writePng("blank.png");
        TikaDocumentParser textParser = mock(TikaDocumentParser.class);
        VisionClient visionClient = mock(VisionClient.class);
        when(visionClient.available()).thenReturn(true);
        when(visionClient.describe(any(), any(), any())).thenReturn("   ");

        ImageAwareDocumentParser parser = new ImageAwareDocumentParser(textParser, visionClient);

        assertThat(parser.parse(file.toFile())).isEmpty();
    }

    private Path writeText(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    /** 写入最小但合法的 PNG 头部，确保 Tika 能按魔数识别为 image/png */
    private Path writePng(String name) throws IOException {
        Path file = tempDir.resolve(name);
        byte[] png = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'
        };
        Files.write(file, png);
        return file;
    }
}
