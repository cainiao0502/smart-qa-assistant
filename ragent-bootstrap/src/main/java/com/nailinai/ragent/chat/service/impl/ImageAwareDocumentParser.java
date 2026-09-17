package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.DocumentParser;
import com.nailinai.ragent.infra.vision.VisionClient;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

/**
 * 支持图片理解的文档解析器（默认实现）。
 *
 * <p><b>解决的问题</b>：图片型文档（扫描件、截图、图表）经 Tika 解析后正文为空，
 * 后续切分得到零个切片，检索永远命中不了——用户传了图，却问不出任何东西，
 * 而且**没有任何报错**，属于最隐蔽的一类失效。
 *
 * <p><b>做法</b>：解析前先用 Tika 检测 MIME 类型。若为受支持的图片且视觉模型可用，
 * 则调用视觉模型生成文本描述，把描述当作正文走原有切分/向量化/检索链路——
 * 下游完全不需要知道「这段文字其实来自一张图」。未配置视觉模型时，
 * 退化为纯文本解析并打日志说明原因，而不是静默失败。
 */
@Service
@Primary
public class ImageAwareDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageAwareDocumentParser.class);

    /** 主流视觉模型普遍支持的格式；gif / bmp 等兼容性差，暂不纳入 */
    private static final Set<String> SUPPORTED_IMAGE_MIME = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private static final String DESCRIBE_PROMPT = """
            请描述这张图片中全部可见的信息，供后续检索问答使用。
            要求：
            1. 先说明图片类型（截图 / 照片 / 图表 / 流程图 / 表格 / 扫描件）。
            2. 描述其中的文字、数据与结构；若包含表格或图表，请写出关键数据与结论。
            3. 图中可读的文字尽量原样抄录，不要改写术语与数字。
            4. 不要推测图中不存在的内容；看不清的部分明确写「看不清」。
            直接输出纯文本，不要使用 Markdown 代码块。
            """;

    private final DocumentParser textParser;
    private final VisionClient visionClient;
    private final Tika detector = new Tika();

    public ImageAwareDocumentParser(TikaDocumentParser textParser, VisionClient visionClient) {
        this.textParser = textParser;
        this.visionClient = visionClient;
    }

    @Override
    public String parse(File file) {
        String mimeType = detectMimeType(file);
        if (!SUPPORTED_IMAGE_MIME.contains(mimeType)) {
            return textParser.parse(file);
        }
        if (!visionClient.available()) {
            log.info("Document {} is an image ({}), but no vision model is configured. "
                            + "It will be indexed as empty text and will never be retrievable. "
                            + "Configure ai.vision.* to enable image understanding.",
                    file.getName(), mimeType);
            return textParser.parse(file);
        }
        return describeImage(file, mimeType);
    }

    private String describeImage(File file, String mimeType) {
        try {
            byte[] imageBytes = Files.readAllBytes(file.toPath());
            String description = visionClient.describe(imageBytes, mimeType, DESCRIBE_PROMPT);
            if (!StringUtils.hasText(description)) {
                log.warn("Vision model returned an empty description for {}", file.getName());
                return "";
            }
            // 保留来源标记：检索命中时能看出这段文字是「图片的描述」而非原文
            return "[图片内容描述]\n" + description.trim();
        } catch (Exception ex) {
            // 视觉模型不可用不应导致整份文档入库失败，退化为纯文本解析
            log.warn("Failed to describe image {}, falling back to plain-text parsing", file.getName(), ex);
            return textParser.parse(file);
        }
    }

    private String detectMimeType(File file) {
        try {
            return detector.detect(file);
        } catch (Exception ex) {
            log.warn("Failed to detect mime type for {}, treating it as plain text", file.getName(), ex);
            return "application/octet-stream";
        }
    }
}
