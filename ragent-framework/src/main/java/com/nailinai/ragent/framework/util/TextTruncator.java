package com.nailinai.ragent.framework.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具输出截断工具。
 *
 * <p>设计参照 pi 的 {@code harness/utils/truncate.ts}，解决过去「工具输出要么全量返回、
 * 要么被无声丢弃」的问题：
 * <ul>
 *   <li><b>双重上限</b>：字符数与行数各设上限，谁先触达以谁为准；</li>
 *   <li><b>只保留完整行</b>：绝不返回半行，避免把结构化内容切坏；</li>
 *   <li><b>返回完整元数据</b>：调用方据此可拼出「如何继续读取」的提示，而不是把内容悄悄丢掉。</li>
 * </ul>
 *
 * <p>计数口径：按 Java {@code char} 计数，一个常用汉字计 1（UTF-8 下约占 3 字节）。
 */
public final class TextTruncator {

    /** 默认字符上限：约 16K 汉字，大致对应 50KB UTF-8 字节 */
    public static final int DEFAULT_MAX_CHARS = 16_000;

    /** 默认行数上限 */
    public static final int DEFAULT_MAX_LINES = 2_000;

    private TextTruncator() {
    }

    /** 从头部保留内容（适合文档 / 文件读取） */
    public static TruncationResult truncateHead(String content) {
        return truncateHead(content, DEFAULT_MAX_CHARS, DEFAULT_MAX_LINES);
    }

    public static TruncationResult truncateHead(String content, int maxChars, int maxLines) {
        int safeMaxChars = Math.max(1, maxChars);
        int safeMaxLines = Math.max(1, maxLines);

        if (content == null || content.isEmpty()) {
            return new TruncationResult("", false, null, 0, 0, 0, 0, safeMaxChars, safeMaxLines);
        }

        int totalChars = content.length();
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        if (totalChars <= safeMaxChars && totalLines <= safeMaxLines) {
            return new TruncationResult(content, false, null, totalChars, totalChars,
                    totalLines, totalLines, safeMaxChars, safeMaxLines);
        }

        List<String> kept = new ArrayList<>();
        int usedChars = 0;
        String truncatedBy = "chars";
        for (int index = 0; index < totalLines && index < safeMaxLines; index++) {
            String line = lines[index];
            int lineChars = line.length() + (index > 0 ? 1 : 0);
            if (usedChars + lineChars > safeMaxChars) {
                truncatedBy = "chars";
                break;
            }
            kept.add(line);
            usedChars += lineChars;
        }
        if (kept.size() >= safeMaxLines) {
            truncatedBy = "lines";
        }

        String output = String.join("\n", kept);
        return new TruncationResult(output, true, truncatedBy, totalChars, output.length(),
                totalLines, kept.size(), safeMaxChars, safeMaxLines);
    }

    /**
     * 截断结果。
     *
     * @param truncatedBy 触达的上限类型：{@code "chars"} / {@code "lines"}；未截断时为 null
     */
    public record TruncationResult(
            String content,
            boolean truncated,
            String truncatedBy,
            int totalChars,
            int outputChars,
            int totalLines,
            int outputLines,
            int maxChars,
            int maxLines
    ) {
        /** 通用续读提示；调用方可据此拼接更贴合业务的动作建议 */
        public String continuationHint() {
            if (!truncated) {
                return "";
            }
            return "[Truncated: showing %d of %d chars (%d of %d lines).]"
                    .formatted(outputChars, totalChars, outputLines, totalLines);
        }
    }
}
