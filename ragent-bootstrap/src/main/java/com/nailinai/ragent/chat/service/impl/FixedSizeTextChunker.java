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
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
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
