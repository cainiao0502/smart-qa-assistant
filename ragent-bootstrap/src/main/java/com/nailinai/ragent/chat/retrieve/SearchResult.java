package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchResult {

    /** 主命中通道：rawScore 对应的通道（多通道命中时 = 归一分最高的那个），兼容旧消费方 */
    private final String channel;
    private final DocumentChunk chunk;
    private final double rawScore;

    /**
     * 通道 → 该通道归一分，保留全部命中身份（不可变）。
     *
     * <p>历史缺陷：本类只有单个 channel 字段，去重时双通道共同命中的切片只保留
     * 「分数较高」一侧的身份——而关键词通道的归一 ts_rank 第一名恒为 1.0，几乎总能
     * 赢过 cosine，于是向量通道的语义证据被丢弃，该切片在重排时被当成「无语义证据」
     * 处理，死于阈值。多通道身份保留后，重排可以取向量侧的语义分量，RRF 也能跨通道
     * 叠加证据。</p>
     */
    private final Map<String, Double> channelScores;

    public SearchResult(String channel, DocumentChunk chunk, double rawScore) {
        this.channel = channel;
        this.chunk = chunk;
        this.rawScore = rawScore;
        this.channelScores = Map.of(channel, rawScore);
    }

    private SearchResult(DocumentChunk chunk, LinkedHashMap<String, Double> channelScores) {
        Map.Entry<String, Double> primary = channelScores.entrySet().iterator().next();
        this.channel = primary.getKey();
        this.chunk = chunk;
        this.rawScore = primary.getValue();
        this.channelScores = Collections.unmodifiableMap(channelScores);
    }

    /** 多通道合并工厂：{@code channelScores} 的第一个条目视为主通道（应为分数最高者） */
    public static SearchResult merged(DocumentChunk chunk, LinkedHashMap<String, Double> channelScores) {
        return new SearchResult(chunk, channelScores);
    }

    /** 命中通道数：多通道共同命中是跨通道证据，去重排序时优先 */
    public int channelHitCount() {
        return channelScores.size();
    }

    /** 该切片在指定通道的归一分；未被该通道命中返回 null */
    public Double scoreFrom(String channelName) {
        return channelScores.get(channelName);
    }

    public boolean hasChannel(String channelName) {
        return channelScores.containsKey(channelName);
    }

    public Map<String, Double> channelScores() {
        return channelScores;
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
