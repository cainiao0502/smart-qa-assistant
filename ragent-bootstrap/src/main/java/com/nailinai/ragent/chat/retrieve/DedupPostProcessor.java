package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多通道合并去重后处理器。
 *
 * <p>同一切片被多个通道命中时，<strong>合并全部通道身份</strong>而不是只保留一个——
 * 旧实现按 rawScore 择一保留，而关键词通道的归一 ts_rank 第一名恒为 1.0、向量通道
 * 是 cosine（量纲不可比），择一的结果是向量侧的语义证据被系统性丢弃，共同命中的
 * 切片反而在阈值处被杀（S0-② 缺陷链的第二环）。</p>
 *
 * <p>输出排序（用户可见于重排关闭时）按「命中通道数 → 通道内排名」：
 * 多通道共同命中（跨通道证据）优先，其后按各通道内排名升序——不再用跨通道不可比的
 * rawScore 直接定序。通道内排名用标准竞赛排名（严格大于者 +1），与 RRF 的口径一致。</p>
 */
@Component
public class DedupPostProcessor implements SearchPostProcessor {

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        // 1) 按 chunkId 聚合通道身份：channel → 该通道最高归一分（同通道重复按高分，防御性）
        Map<Long, LinkedHashMap<String, Double>> scoresByChunk = new LinkedHashMap<>();
        Map<Long, DocumentChunkRef> primaryByChunk = new HashMap<>();
        for (SearchResult result : inputs) {
            Long chunkId = result.chunkId();
            if (chunkId == null) {
                continue;
            }
            LinkedHashMap<String, Double> scores =
                    scoresByChunk.computeIfAbsent(chunkId, k -> new LinkedHashMap<>());
            scores.merge(result.channel(), result.rawScore(), Math::max);

            DocumentChunkRef primary = primaryByChunk.get(chunkId);
            if (primary == null || result.rawScore() > primary.rawScore) {
                primaryByChunk.put(chunkId, new DocumentChunkRef(result.chunk(), result.rawScore()));
            }
        }

        // 2) 生成合并结果
        List<SearchResult> merged = new ArrayList<>(scoresByChunk.size());
        for (Map.Entry<Long, LinkedHashMap<String, Double>> entry : scoresByChunk.entrySet()) {
            LinkedHashMap<String, Double> scores = entry.getValue();
            // 主通道 = 分数最高的命中，排在 map 首位（与旧「保留最高分」的语义一致）
            LinkedHashMap<String, Double> ordered = new LinkedHashMap<>();
            scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(e -> ordered.put(e.getKey(), e.getValue()));
            merged.add(SearchResult.merged(primaryByChunk.get(entry.getKey()).chunk, ordered));
        }

        // 3) 排序：命中通道数降序 → 各通道内最佳排名升序 → 主通道 rawScore 降序兜底
        return merged.stream()
                .sorted(Comparator
                        .comparingInt(SearchResult::channelHitCount).reversed()
                        .thenComparingInt(result -> bestChannelRank(result, merged))
                        .thenComparing(SearchResult::rawScore, Comparator.reverseOrder()))
                .toList();
    }

    /** 该切片在其所有命中通道中的最佳排名（1 起）；排名在合并后的候选集内按通道计算 */
    private int bestChannelRank(SearchResult target, List<SearchResult> all) {
        int best = Integer.MAX_VALUE;
        for (Map.Entry<String, Double> hit : target.channelScores().entrySet()) {
            int rank = 1;
            for (SearchResult other : all) {
                Double score = other.scoreFrom(hit.getKey());
                if (score != null && score > hit.getValue()) {
                    rank++;
                }
            }
            best = Math.min(best, rank);
        }
        return best;
    }

    /** 主通道命中的 chunk 实例与其归一分（chunk 实例承载文本/元数据，score 字段语义随主通道） */
    private record DocumentChunkRef(DocumentChunk chunk, double rawScore) {
    }
}
