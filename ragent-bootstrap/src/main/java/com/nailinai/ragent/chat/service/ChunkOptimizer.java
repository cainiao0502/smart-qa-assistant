package com.nailinai.ragent.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ChunkOptimizer optimizes the granularity of SSE chunks sent to the frontend.
 * - Small chunks (≤5 chars) are merged within a 30ms window before sending
 * - Ensures no multi-byte UTF-8 character truncation (CJK, emoji)
 * - flush() immediately sends all buffered content on stream end
 *
 * <p><b>大 chunk 拆片限速（>50 字符拆成 ≤20 字符子片、每片 sleep 10ms）仅用于
 * 前端打字机演示效果，且与「流式降低感知延迟」的目标相悖：一个 5000 字符的回答
 * 会被人为加约 2.5s 延迟。该路径默认关闭（{@code splitLargeChunks=false}）；
 * 打字机动画应由前端对已收到的文本自行渲染。</b>合并窗口（减少 SSE 事件数）保留，
 * 始终生效。</p>
 *
 * <p>历史缺陷：accept() 是 synchronized，而大 chunk 的拆分发射在锁内逐片 sleep(10ms)，
 * 一个 5 万字符的输出会持锁约 25 秒，同实例的 flush()/后续 accept() 全部被卡住。
 * 现在锁只保护共享状态（合并缓冲、flushed 标记）的读写，拆片发射与间隔 sleep 都在锁外执行。
 * 同一条 LLM 流的 accept() 由单线程顺序调用，锁外发射不改变该流内的输出顺序。
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
    /** 大 chunk 拆片限速开关：默认关闭（见类注释）；仅供演示场景显式开启 */
    private final boolean splitLargeChunks;

    public ChunkOptimizer(Consumer<String> emitCallback, ScheduledExecutorService scheduler) {
        this(emitCallback, scheduler, false);
    }

    public ChunkOptimizer(Consumer<String> emitCallback, ScheduledExecutorService scheduler, boolean splitLargeChunks) {
        this.emitCallback = emitCallback;
        this.scheduler = scheduler;
        this.splitLargeChunks = splitLargeChunks;
    }

    /**
     * Accept a chunk from the LLM and apply optimization before emitting.
     */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty() || flushed) {
            return;
        }

        List<String> parts = new ArrayList<>();
        synchronized (this) {
            int length = chunk.length();

            if (length <= SMALL_CHUNK_THRESHOLD) {
                // Small chunk: add to merge buffer and schedule delayed emit
                mergeBuffer.append(chunk);
                scheduleMergeFlush();
                return;
            }
            // Medium/large chunk: flush any pending merge buffer first
            flushMergeBuffer();
            if (length > LARGE_CHUNK_THRESHOLD && splitLargeChunks) {
                // 纯计算：只切分，不在锁内发射或 sleep
                splitIntoParts(chunk, parts);
            } else {
                // 拆片限速关闭时大 chunk 也直发，不人为加打字机延迟
                parts.add(chunk);
            }
        }

        // 锁外逐片发射：emit 回调（写 SSE）与间隔 sleep 都不再持有监视器锁
        for (int i = 0; i < parts.size(); i++) {
            emit(parts.get(i));
            if (i < parts.size() - 1 && !sleepInterval()) {
                // 中断：立即发完剩余内容
                for (int j = i + 1; j < parts.size(); j++) {
                    emit(parts.get(j));
                }
                return;
            }
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
     * Split a large chunk into parts of at most MAX_SUB_CHUNK_SIZE characters,
     * ensuring no multi-byte (surrogate pair) character is split. Pure computation.
     */
    private void splitIntoParts(String chunk, List<String> parts) {
        int offset = 0;
        int length = chunk.length();

        while (offset < length) {
            int end = Math.min(offset + MAX_SUB_CHUNK_SIZE, length);
            // Ensure we don't split a surrogate pair
            if (end < length && Character.isHighSurrogate(chunk.charAt(end - 1))) {
                end = Math.min(end + 1, length);
            }
            parts.add(chunk.substring(offset, end));
            offset = end;
        }
    }

    /** 间隔等待；被中断时恢复中断标志并返回 false，由调用方决定立即发完剩余内容 */
    private boolean sleepInterval() {
        try {
            Thread.sleep(SPLIT_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
     * Flush the merge buffer content if non-empty. Must be called while holding the monitor.
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
                // SSE 连接已断等情况：内容静默丢弃是预期降级，但留痕便于排查
                log.debug("Failed to emit chunk: {}", e.getMessage());
            }
        }
    }
}
