package com.nailinai.ragent.chat.retrieve;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 阈值过滤 + 结果截断后处理器（order 300，处理器链末位）。
 *
 * <p><b>为什么阈值放进处理器链：</b>检索归属/阈值这类「评估与生产必须同构」的过滤，
 * 一旦由调用方（RetrievalServiceImpl）各自实现，评估路径的手动装配就会与生产悄悄分叉
 * （cc09b8f 修复的三处评估缺陷同源）。放进链里，评估复用 Spring 装配的生产处理器链时
 * 自动继承同一套阈值逻辑。</p>
 *
 * <p><b>为什么截断（top-k）也放在这里（C2）：</b>旧实现把 {@code limit(topK)} 放在
 * {@code RerankPostProcessor}（order 200），带来两个问题：①<b>席位不回填</b>——top-k 里
 * 被阈值否掉的席位没人补，第 k+1 名以后明明有能过阈值的候选也进不来；②<b>重排关闭时
 * 全链无截断点</b>——{@code app.rag.rerank.enabled=false} 时去重返回全部候选、阈值只过滤
 * 不截断，最终回答 Prompt 可能收到数倍于 top-k 的切片（每片还会被 small-to-big 扩成
 * 上千字）。移到链末位后语义变成「<b>先筛后取前 K</b>」：候选池保持完整，过滤掉不合格的
 * 之后再截断，两个问题一并消失，且与重排开关无关。</p>
 *
 * <p><b>按量纲分开标定（S0-②）：</b>不同证据类型的分数量纲不可比，同一个阈值一刀切
 * 会把关键词通道整体绞杀——启发式重排下关键词独有命中的 rerankScore 上限只剩
 * lexical-weight（0.25），对 cosine 量纲标定的 0.6 阈值恒不可达（S0-② 第三环）。规则：</p>
 * <ul>
 *   <li>有向量证据（双通道或向量独有命中）：融合分（rerankScore，无重排时为 cosine）
 *       ≥ 通用阈值（1 - reference-distance-threshold 换算）；cross-encoder 生效时
 *       其分数量纲不同，改用 {@code app.rag.rerank.cross-encoder.min-fused-score}
 *       （≤0 表示沿用通用阈值，换供应商后需按消融重标定）；</li>
 *   <li>关键词独有命中：没有语义分量，融合分量纲不可比——用关键词通道自己的
 *       归一 ts_rank 门槛 {@code app.rag.channel.keyword.min-score}（默认 0：
 *       通道 SQL 已保证存在真实 tsquery 命中，体量交给排序与 top-k 竞争）。</li>
 * </ul>
 */
@Component
public class ThresholdFilterPostProcessor implements SearchPostProcessor {

    /** 关键词独有命中的门槛（归一 ts_rank 量纲）；0 = 只要求通道真实命中 */
    private final double keywordMinScore;
    /** cross-encoder 分数量纲下的融合分阈值；≤0 = 沿用通用阈值（context.scoreThreshold） */
    private final double crossEncoderMinFusedScore;

    public ThresholdFilterPostProcessor(
            @Value("${app.rag.channel.keyword.min-score:0.0}") double keywordMinScore,
            @Value("${app.rag.rerank.cross-encoder.min-fused-score:0.0}") double crossEncoderMinFusedScore) {
        this.keywordMinScore = Math.max(0.0, keywordMinScore);
        this.crossEncoderMinFusedScore = crossEncoderMinFusedScore;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public List<SearchResult> process(List<SearchResult> inputs, SearchContext context) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        boolean crossEncoderActive = context.hasFlag(SearchContext.FLAG_CROSS_ENCODER);
        // 先筛后取前 K：候选池来自上游全量（RerankPostProcessor 不再截断），
        // 过滤掉不合格者之后再截断——被否掉的席位可由更靠后的候选回填，
        // 且重排关闭时依然保证不超过 top-k。
        return inputs.stream()
                .filter(result -> passesThreshold(result, context, crossEncoderActive))
                .limit(context.topK())
                .toList();
    }

    private boolean passesThreshold(SearchResult result, SearchContext context, boolean crossEncoderActive) {
        if (result.hasChannel(VectorSearchChannel.CHANNEL_NAME)) {
            double threshold = crossEncoderActive && crossEncoderMinFusedScore > 0
                    ? crossEncoderMinFusedScore
                    : context.scoreThreshold();
            return vectorEvidenceScore(result) >= threshold;
        }
        // 关键词独有命中：关键词通道自己的量纲（归一 ts_rank）
        return result.rawScore() >= keywordMinScore;
    }

    /**
     * 向量证据分支的融合归一分：重排生效时用 rerankScore；无重排时必须回退到
     * <b>向量侧</b>归一分（cosine）。
     *
     * <p><b>为什么不能回退 rawScore（B1）：</b>{@code rawScore()} 是去重合并后的
     * <b>主通道分</b>，而关键词通道的归一 ts_rank 第一名恒为 1.0，会赢过 cosine 成为主通道。
     * 若在此回退 rawScore，一个 cosine 仅 0.30 的切片只要关键词排名更高，就会拿着
     * 1.0 的关键词分通过 0.6 的<b>向量</b>阈值——向量阈值对「关键词排第一 + 向量共同命中」
     * 的切片形同虚设。这是 S0-② 那类「跨量纲直接比较」从融合层漏到阈值层的残留。
     * 触发路径有两条：{@code app.rag.rerank.enabled=false}（重排不在链上），
     * 或 cross-encoder 启用但调用失败（RerankPostProcessor 提前返回，不写 rerankScore）。</p>
     *
     * <p>调用方已判断 {@code hasChannel(vector)}，故 {@code scoreFrom} 理论上非 null；
     * 末位兜底仅防御 channelScores 装配异常，不改变正常路径行为。</p>
     */
    private double vectorEvidenceScore(SearchResult result) {
        Double rerankScore = result.chunk().getRerankScore();
        if (rerankScore != null) {
            return rerankScore;
        }
        Double vectorScore = result.scoreFrom(VectorSearchChannel.CHANNEL_NAME);
        return vectorScore != null ? vectorScore : result.rawScore();
    }
}
