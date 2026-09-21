package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.TextChunker;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

public class FixedSizeTextChunker implements TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public FixedSizeTextChunker(@Value("${app.rag.chunk-size}") int chunkSize,
                                @Value("${app.rag.chunk-overlap}") int chunkOverlap) {
        // 参数守卫：chunkSize 至少为 1；overlap 必须 < 步长，否则 start 每轮只前进
        // Math.max(end-overlap, start+1) 的 +1 兜底，切片数按字符粒度爆炸（O(n·chunkSize)）。
        // 这里把 overlap 钳制到 [0, chunkSize/2)，既保住重叠语义又杜绝配置失误。
        this.chunkSize = Math.max(1, chunkSize);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize / 2));
    }

    @Override
    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return chunks;
    }
}
