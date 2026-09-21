package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.TextChunker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切分器：按句子/段落/标题层级切分文本，替代纯固定长度切分。
 * <p>
 * 切分策略：
 * 1. 先按标题（#、##、===、---）拆分为大段
 * 2. 再按段落（连续两个换行）拆分
 * 3. 段落内按句子边界（。！？.!?）切分
 * 4. 合并小片段直到接近 chunkSize，保留 chunkOverlap 的重叠
 * <p>
 * 每个 chunk 尽量保持语义完整，不会在句子中间截断。
 */
@Service
public class SemanticTextChunker implements TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public SemanticTextChunker(@Value("${app.rag.chunk-size}") int chunkSize,
                               @Value("${app.rag.chunk-overlap}") int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> segments = splitIntoSegments(content.trim());
        if (segments.isEmpty()) {
            return List.of();
        }

        // 兜底上界：无标点的长句（base64、长 URL 等）会生成超过 chunkSize 的 segment，
        // 直接合并会让单个 chunk 超出 embedding 的 token 上限，导致整个文档入库失败。
        // 对超长 segment 做字符级强制切分，保证任何输出 chunk <= chunkSize。
        List<String> bounded = new ArrayList<>(segments.size());
        for (String segment : segments) {
            bounded.addAll(forceSplitOversized(segment));
        }

        return mergeSegments(bounded);
    }

    /**
     * 将文本拆分为最小语义单元（句子/标题/段落）。
     * 每个 segment 是一个不可再分的语义片段。
     */
    private List<String> splitIntoSegments(String content) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        String[] lines = content.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();

            // 标题行作为独立 segment
            if (isHeadingLine(trimmed)) {
                flushSegment(current, segments);
                if (!trimmed.isEmpty()) {
                    segments.add(trimmed);
                }
                continue;
            }

            // 空行 = 段落边界
            if (trimmed.isEmpty()) {
                flushSegment(current, segments);
                continue;
            }

            // 段落内按句子边界拆分
            List<String> sentences = splitSentences(trimmed);
            for (String sentence : sentences) {
                if (sentence.isEmpty()) continue;

                if (current.length() + sentence.length() + 1 > chunkSize && current.length() > 0) {
                    flushSegment(current, segments);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence);
            }
        }
        flushSegment(current, segments);

        return segments;
    }

    /**
     * 将文本拆分为最小语义单元（句子/标题/段落）。
     * 每个 segment 是一个不可再分的语义片段。
     */

    /**
     * 单个 segment 超过 chunkSize 时做字符级强制循环切分，保证每片 <= chunkSize。
     * 切点尽量靠近软边界（空白/标点），找不到时按 UTF-16 代理对安全的硬切点截断，
     * 不会把 emoji 等增补字符切成孤独代理项。
     */
    private List<String> forceSplitOversized(String segment) {
        if (segment.length() <= chunkSize) {
            return List.of(segment);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < segment.length()) {
            int end = Math.min(start + chunkSize, segment.length());
            if (end < segment.length()) {
                end = findHardCutPoint(segment, start, end);
            }
            String piece = segment.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = end;
        }
        return pieces;
    }

    /**
     * 在 (start, limit] 内从后往前找软边界切点（空白或句读标点）；
     * 找不到则回退到 limit 处硬切，并回退避开代理对中间。
     */
    private int findHardCutPoint(String text, int start, int limit) {
        for (int i = limit; i > start + 1; i--) {
            char c = text.charAt(i - 1);
            boolean softBoundary = Character.isWhitespace(c) || isSentenceEnd(c)
                    || c == '，' || c == '、' || c == '；' || c == '：'
                    || c == ',' || c == ';' || c == ':';
            if (softBoundary && !Character.isLowSurrogate(text.charAt(i))) {
                return i;
            }
        }
        // 硬切：切点不能落在代理对中间（charAt(limit) 是低位代理时，limit-1 是其高位代理）
        int cut = limit;
        while (cut > start + 1 && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return cut;
    }

    /**
     * 合并小 segment 为 chunk，每个 chunk 不超过 chunkSize，
     * chunk 之间保留 chunkOverlap 的重叠文本。
     */
    private List<String> mergeSegments(List<String> segments) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
    
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);

            if (chunk.length() + segment.length() + 1 > chunkSize && chunk.length() > 0) {
                chunks.add(chunk.toString().trim());
                // 计算重叠：从当前 chunk 末尾取 overlap 文本
                String overlapText = computeOverlap(chunk.toString());
                chunk = new StringBuilder();
                if (!overlapText.isEmpty()) {
                    chunk.append(overlapText);
                }
            }

            if (chunk.length() > 0) {
                chunk.append('\n');
            }
            chunk.append(segment);
        }

        if (chunk.length() > 0) {
            chunks.add(chunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 从已完成的 chunk 文本中截取尾部 overlap 长度的内容，
     * 尽量在句子边界处截断以保持语义完整。
     */
    private String computeOverlap(String chunkText) {
        if (chunkOverlap <= 0 || chunkText.length() <= chunkOverlap) {
            return "";
        }

        String tail = chunkText.substring(chunkText.length() - chunkOverlap);
        // 尝试在句子边界处截断
        int sentenceBreak = findSentenceBreak(tail);
        if (sentenceBreak > 0) {
            return tail.substring(sentenceBreak).trim();
        }
        return tail.trim();
    }

    /**
     * 在文本开头附近查找句子边界位置。
     */
    private int findSentenceBreak(String text) {
        for (int i = 0; i < Math.min(text.length(), 40); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        // 回退到空格边界
        int spaceIdx = text.indexOf(' ');
        return spaceIdx > 0 ? spaceIdx + 1 : 0;
    }

    /**
     * 按中英文句子边界切分一行文本。
     */
    private List<String> splitSentences(String line) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            current.append(c);

            if (isSentenceEnd(c)) {
                // 英文句点要区分「句末」与「小数点 / 标识符分隔符」：
                // 仅当后一个字符是空白或已到行尾时才视为句子结束。
                // 否则 0.60 / chunk.size / v3.2 / hnsw.ef_search 会被切成 "0. 60"
                // 这类畸形文本 —— 既破坏关键词通道的词面匹配，也污染向量语义表示。
                if (c == '.' && i + 1 < line.length() && !Character.isWhitespace(line.charAt(i + 1))) {
                    continue;
                }

                // 检查下一个字符是否也是句子结束符（如 ...）或引号
                if (i + 1 < line.length()) {
                    char next = line.charAt(i + 1);
                    if (isSentenceEnd(next) || next == '"' || next == '」' || next == '』' || next == ')') {
                        continue;
                    }
                }
                String s = current.toString().trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                current = new StringBuilder();
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }

        return sentences;
    }

    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
    }

    private boolean isHeadingLine(String line) {
        if (line.isEmpty()) return false;
        // Markdown ATX 标题: # ## ### 等
        if (line.startsWith("#")) return true;
        // Markdown Setext 标题: === 或 ---
        if (line.matches("=+|-+")) return true;
        return false;
    }

    private void flushSegment(StringBuilder current, List<String> segments) {
        String s = current.toString().trim();
        if (!s.isEmpty()) {
            segments.add(s);
        }
        current.setLength(0);
    }
}
