package com.nailinai.ragent.chat.retrieve;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每文档席位上限（结果多样性）后处理器。
 *
 * <p>动机来自 2026-09-27 线上评估：单切片小文档（1 chunk）的查询在 top-k 下
 * 稳定 miss——多切片大文档凭词面相关性占满全部 k 个名额，把唯一命中的小文档
 * 挤出局。本处理器在去重后的排名序列上限制单个文档最多占 {@code maxPerDoc} 席，
 * 被挤出的小文档切片得以进入最终结果。</p>
 *
 * <p>顺序假设：本处理器位于 DedupPostProcessor(100) 之后（order 150），
 * 输入已按分数降序排列——逐条扫描时先到先得，同文档只保留排名最高的前 N 席。
 * 位于 RerankPostProcessor(200) 之前意味着：重排开启时重排会按 rerankScore
 * 重新排序并截断 top-k，本处理器的多样性效果可能被重排覆盖（重排路径的多样性
 * 由重排模型自身的相关性判断保证）；重排关闭时本处理器的席位约束直接生效。</p>
 *
 * <p>{@code maxPerDoc <= 0} 时直通（默认关闭，由 app.rag.diversity.max-per-doc 控制）。</p>
 */
@Component
public class DocDiversityPostProcessor implements SearchPostProcessor {

    private final int maxPerDoc;

    public DocDiversityPostProcessor(@Value("${app.rag.diversity.max-per-doc:0}") int maxPerDoc) {
        this.maxPerDoc = Math.max(0, maxPerDoc);
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (maxPerDoc <= 0 || inputs == null || inputs.isEmpty()) {
            return inputs;
        }

        Map<Long, Integer> seatsByDoc = new LinkedHashMap<>();
        List<SearchResult> output = new ArrayList<>(inputs.size());
        for (SearchResult result : inputs) {
            Long docId = result.chunk() == null ? null : result.chunk().getDocId();
            if (docId == null) {
                output.add(result);
                continue;
            }
            int used = seatsByDoc.merge(docId, 1, Integer::sum);
            if (used <= maxPerDoc) {
                output.add(result);
            }
        }
        return output;
    }
}
