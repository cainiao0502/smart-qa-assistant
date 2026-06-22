package com.nailinai.ragent.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * ChunkOptimizer optimizes the granularity of SSE chunks sent to the frontend.
 * - Large chunks (>50 chars) are split into ≤20 char sub-chunks with 10ms intervals
 * - Small chunks (≤5 chars) are merged within a 30ms window before sending
 * - Ensures no multi-byte UTF-8 character truncation (CJK, emoji)
 * - flush() immediately sends all buffered content on stream end
 */
public class ChunkOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ChunkOptimizer.class);

    private static final int LARGE_CHUNK_THRESHOLD = 50;
    private static final int MAX_SUB_CHUNK_SIZE = 20;
    private static final int SMALL_CHUNK_THRESHOLD = 5;
    private static final long MERGE_WINDOW_MS = 30;
    private static final long SPLIT_INTERVAL_MS = 10;

    private final Consumer<String> emitCallback;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder mergeBuffer = new StringBuilder();
    private ScheduledFuture<?> mergeTask;
    private volatile boolean flushed = false;

    public ChunkOptimizer(Consumer<String> emitCallback, ScheduledExecutorService scheduler) {
        this.emitCallback = emitCallback;
        this.scheduler = scheduler;
    }

    /**
     * Accept a chunk from the LLM and apply optimization before emitting.
     */
    public synchronized void accept(String chunk) {
        if (chunk == null || chunk.isEmpty() || flushed) {
            return;
        }

        int length = chunk.length();

        if (length > LARGE_CHUNK_THRESHOLD) {
            // Flush any pending merge buffer first
            flushMergeBuffer();
            // Split large chunk into sub-chunks respecting character boundaries
            splitAndEmit(chunk);
        } else if (length <= SMALL_CHUNK_THRESHOLD) {
            // Small chunk: add to merge buffer and schedule delayed emit
            mergeBuffer.append(chunk);
            scheduleMergeFlush();
        } else {
            // Medium chunk (6-50 chars): flush merge buffer then emit directly
            flushMergeBuffer();
            emit(chunk);
        }
    }

    /**
     * Flush all buffered content immediately. Called on stream end.
     */
    public synchronized void flush() {
        if (flushed) {
            return;
        }
        flushed = true;
        cancelMergeTask();
        flushMergeBuffer();
    }

    /**
     * Split a large chunk into sub-chunks of at most MAX_SUB_CHUNK_SIZE characters,
     * ensuring no multi-byte character is split.
     */
    private void splitAndEmit(String chunk) {
        int offset = 0;
        int length = chunk.length();

        while (offset < length) {
            int end = Math.min(offset + MAX_SUB_CHUNK_SIZE, length);
            // Ensure we don't split a surrogate pair
            if (end < length && Character.isHighSurrogate(chunk.charAt(end - 1))) {
                end = Math.min(end + 1, length);
            }
            String subChunk = chunk.substring(offset, end);
            emit(subChunk);

            offset = end;

            // Add interval between sub-chunks (except after the last one)
            if (offset < length) {
                try {
                    Thread.sleep(SPLIT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Emit remaining content immediately on interrupt
                    if (offset < length) {
                        emit(chunk.substring(offset));
                    }
                    return;
                }
            }
        }
    }

    /**
     * Schedule a merge buffer flush after MERGE_WINDOW_MS.
     * If a task is already scheduled, it remains (the window started with the first small chunk).
     */
    private void scheduleMergeFlush() {
        if (mergeTask == null || mergeTask.isDone()) {
            mergeTask = scheduler.schedule(() -> {
                synchronized (ChunkOptimizer.this) {
                    flushMergeBuffer();
                }
            }, MERGE_WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Flush the merge buffer content if non-empty.
     */
    private void flushMergeBuffer() {
        cancelMergeTask();
        if (mergeBuffer.length() > 0) {
            String content = mergeBuffer.toString();
            mergeBuffer.setLength(0);
            emit(content);
        }
    }

    private void cancelMergeTask() {
        if (mergeTask != null && !mergeTask.isDone()) {
            mergeTask.cancel(false);
            mergeTask = null;
        }
    }

    private void emit(String content) {
        if (content != null && !content.isEmpty()) {
            try {
                emitCallback.accept(content);
            } catch (Exception e) {
                log.warn("Failed to emit chunk: {}", e.getMessage());
            }
        }
    }
}
