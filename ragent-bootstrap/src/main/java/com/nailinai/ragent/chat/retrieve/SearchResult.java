package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;

public class SearchResult {

    private final String channel;
    private final DocumentChunk chunk;
    private final double rawScore;

    public SearchResult(String channel, DocumentChunk chunk, double rawScore) {
        this.channel = channel;
        this.chunk = chunk;
        this.rawScore = rawScore;
    }

    public String channel() {
        return channel;
    }

    public DocumentChunk chunk() {
        return chunk;
    }

    public double rawScore() {
        return rawScore;
    }

    public Long chunkId() {
        return chunk.getId();
    }

    public static SearchResult of(String channel, DocumentChunk chunk, double rawScore) {
        return new SearchResult(channel, chunk, rawScore);
    }
}