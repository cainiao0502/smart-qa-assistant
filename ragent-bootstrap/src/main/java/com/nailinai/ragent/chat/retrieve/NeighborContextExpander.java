package com.nailinai.ragent.chat.retrieve;

import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 相邻切片上下文扩展（small-to-big 检索）。
 *
 * <p>检索以小切片匹配（精确），但孤立切片可能缺上下文（片段开头引用前文、结尾话没说完）。
 * 参照 Dify parent-child 模式解决的问题：命中后按 {@code (doc_id, chunk_index)} 取相邻切片，
 * 把命中切片的正文扩展为「前一片 + 命中片 + 后一片」，让模型拿到连续文本。
 * 阈值过滤与引用标识（docId + chunkIndex）始终以命中切片为准，相邻切片只贡献正文。</p>
 *
 * <p>去重说明：切分器会在相邻切片之间保留 chunkOverlap 重叠文本（前一片的尾部 ==
 * 后一片的头部的同一段字符串），直接拼接会把重叠内容重复注入 prompt。扩展时按
 * 「邻居尾部 vs 命中片头部」做有界后缀匹配，尽力裁掉重叠部分；匹配不到就原样拼接，
 * 属于尽力而为的优化而非正确性依赖。</p>
 */
@Component
public class NeighborContextExpander {

    private static final Logger log = LoggerFactory.getLogger(NeighborContextExpander.class);

    /** 重叠文本的最长扫描长度：需覆盖 chunkOverlap（默认 80）及其句界裁剪带来的偏移 */
    private static final int MAX_OVERLAP_SCAN = 256;

    private final DocumentChunkMapper documentChunkMapper;
    private final boolean enabled;
    private final int window;

    public NeighborContextExpander(DocumentChunkMapper documentChunkMapper,
                                   @Value("${app.rag.context-expansion.enabled:true}") boolean enabled,
                                   @Value("${app.rag.context-expansion.window:1}") int window) {
        this.documentChunkMapper = documentChunkMapper;
        this.enabled = enabled;
        this.window = Math.max(0, window);
    }

    /** 评估等场景使用：显式关闭扩展，mapper 允许为 null（关闭时不会触达） */
    public static NeighborContextExpander disabled() {
        return new NeighborContextExpander(null, false, 0);
    }

    /**
     * 对命中切片做相邻上下文扩展。
     *
     * @param kbId   知识库 ID（null 时不扩展）
     * @param chunks 阈值过滤后的命中切片（按检索排名排序）
     * @return 扩展后的切片列表（同一批对象，chunkText 原地增强；关闭或无邻居时原样返回）
     */
    public List<DocumentChunk> expand(Long kbId, List<DocumentChunk> chunks) {
        if (!enabled || documentChunkMapper == null || window <= 0
                || kbId == null || chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 按 docId 分组收集需要的切片号：命中号 ± window
        Map<Long, Set<Integer>> wantedByDoc = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getDocId() == null || chunk.getChunkIndex() == null) {
                continue;
            }
            Set<Integer> wanted = wantedByDoc.computeIfAbsent(chunk.getDocId(), k -> new LinkedHashSet<>());
            for (int offset = -window; offset <= window; offset++) {
                int index = chunk.getChunkIndex() + offset;
                if (index >= 0) {
                    wanted.add(index);
                }
            }
        }
        if (wantedByDoc.isEmpty()) {
            return chunks;
        }

        Map<Long, Map<Integer, DocumentChunk>> neighborsByDoc = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Integer>> entry : wantedByDoc.entrySet()) {
            try {
                // 同文档切片量有限且 doc_id 有索引，按文档逐个查询成本可接受
                List<DocumentChunk> fetched = documentChunkMapper.selectByDocIdAndChunkIndexes(
                        kbId, entry.getKey(), List.copyOf(entry.getValue()));
                Map<Integer, DocumentChunk> byIndex = new TreeMap<>();
                for (DocumentChunk neighbor : fetched) {
                    if (neighbor.getChunkIndex() != null) {
                        byIndex.put(neighbor.getChunkIndex(), neighbor);
                    }
                }
                neighborsByDoc.put(entry.getKey(), byIndex);
            } catch (RuntimeException ex) {
                // 扩展是增强而非依赖：查询失败时命中切片保持原文，检索主链路不受影响
                log.warn("neighbor context expansion failed for docId={}: {}", entry.getKey(), ex.getMessage());
                neighborsByDoc.put(entry.getKey(), Map.of());
            }
        }

        for (DocumentChunk chunk : chunks) {
            Map<Integer, DocumentChunk> byIndex = neighborsByDoc.get(chunk.getDocId());
            if (byIndex == null || byIndex.isEmpty() || chunk.getChunkIndex() == null) {
                continue;
            }
            chunk.setChunkText(mergeWithNeighbors(byIndex, chunk));
        }
        return chunks;
    }

    /**
     * 把命中片与前后邻居合并为一段连续文本。
     *
     * <p>去重方向刻意保持「命中片完整、裁邻居」：命中片是引用展示的主体
     * （reference 面板按 docId + chunkIndex 指向它），必须保持原文从真实开头开始；
     * 重叠文本（前邻居尾部 == 命中片头部，或命中片尾部 == 后邻居头部）
     * 统一从邻居一侧裁掉。</p>
     */
    private String mergeWithNeighbors(Map<Integer, DocumentChunk> byIndex, DocumentChunk hit) {
        int hitIndex = hit.getChunkIndex();
        String hitText = hit.getChunkText() == null ? "" : hit.getChunkText();

        StringBuilder merged = new StringBuilder();
        for (int offset = -window; offset <= window; offset++) {
            String segment;
            if (offset == 0) {
                segment = hitText;
            } else {
                DocumentChunk neighbor = byIndex.get(hitIndex + offset);
                if (neighbor == null || neighbor.getChunkText() == null || neighbor.getChunkText().isBlank()) {
                    continue;
                }
                String neighborText = neighbor.getChunkText();
                segment = offset < 0
                        // 前邻居：其尾部可能与命中片头部重复（chunkOverlap），裁掉邻居的重复尾部
                        ? trimOverlapTail(neighborText, hitText)
                        // 后邻居：其头部可能与命中片尾部重复，裁掉邻居的重复头部
                        : trimOverlapHead(neighborText, hitText);
            }
            // 分隔符只在段与段之间追加，缺失的邻居不产生空行
            if (merged.length() > 0) {
                merged.append('\n');
            }
            merged.append(segment);
        }
        return merged.toString();
    }

    /** 裁掉 {@code tail} 文本的尾部：其与 {@code keepWhole} 的头部重复的重叠区 */
    private String trimOverlapTail(String tail, String keepWhole) {
        int overlap = suffixPrefixOverlap(tail, keepWhole);
        return overlap > 0 ? tail.substring(0, tail.length() - overlap) : tail;
    }

    /** 裁掉 {@code head} 文本的头部：其与 {@code keepWhole} 的尾部重复的重叠区 */
    private String trimOverlapHead(String head, String keepWhole) {
        int overlap = suffixPrefixOverlap(keepWhole, head);
        return overlap > 0 ? head.substring(overlap) : head;
    }

    /**
     * 返回 {@code suffixTail} 的尾部与 {@code prefixHead} 的头部相同字符串的长度（0 表示无重叠）。
     * 有界扫描（最长 {@value #MAX_OVERLAP_SCAN} 字符），只用于重叠去重的尽力优化。
     */
    private int suffixPrefixOverlap(String suffixTail, String prefixHead) {
        int max = Math.min(MAX_OVERLAP_SCAN, Math.min(suffixTail.length(), prefixHead.length()));
        for (int length = max; length > 0; length--) {
            if (suffixTail.regionMatches(suffixTail.length() - length, prefixHead, 0, length)) {
                return length;
            }
        }
        return 0;
    }
}
