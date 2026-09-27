package com.nailinai.ragent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 评估集运行入口（默认关闭，通过 {@code app.eval.enabled=true} 开启）。
 *
 * <p>启动后从评估集文件加载查询：
 * <ol>
 *     <li>对"查询改写 + 重排"做 2x2 消融，输出各组合的文档级 recall@k / precision@k；</li>
 *     <li>追加第五列：与全开组合相同、但叠加生产路径阈值（reference-distance-threshold 换算），
 *         用于暴露「阈值过滤吞掉正确答案」的失败模式；</li>
 *     <li>若开启 {@code app.eval.semantic-enabled}，再对每条查询做语义级评估
 *         （LLM-as-judge：faithfulness / answerRelevancy）并输出均值。</li>
 * </ol>
 */
@Component
public class RagEvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationRunner.class);

    private final RagEvaluator ragEvaluator;
    private final RagSemanticEvaluator semanticEvaluator;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean semanticEnabled;
    private final String evaluationSetPath;
    /** 生产路径的融合分阈值：由 reference-distance-threshold（距离）换算而来，用于第五列消融 */
    private final double productionScoreThreshold;
    /**
     * 跳过含查询改写的消融组合：rewrite=true 的组合每条查询都要调 chat 模型，
     * 供应商余额耗尽（402）时重试+熔断会把评估拖到遥遥无期。开启后只跑
     * noRewrite / minimal / threshold(no-rewrite) 三列——它们不依赖 chat 模型。
     */
    private final boolean skipRewrite;

    public RagEvaluationRunner(RagEvaluator ragEvaluator,
                               RagSemanticEvaluator semanticEvaluator,
                               ObjectMapper objectMapper,
                               @Value("${app.eval.enabled:false}") boolean enabled,
                               @Value("${app.eval.semantic-enabled:false}") boolean semanticEnabled,
                               @Value("${app.eval.set-path:data/eval/eval-set.json}") String evaluationSetPath,
                               @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold,
                               @Value("${app.eval.skip-rewrite:false}") boolean skipRewrite) {
        this.ragEvaluator = ragEvaluator;
        this.semanticEvaluator = semanticEvaluator;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.semanticEnabled = semanticEnabled;
        this.evaluationSetPath = evaluationSetPath;
        this.productionScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
        this.skipRewrite = skipRewrite;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }
        File file = new File(evaluationSetPath);
        if (!file.isFile()) {
            log.warn("RAG evaluation skipped: evaluation set not found at {}", evaluationSetPath);
            return;
        }
        RagEvaluationSet set = objectMapper.readValue(file, RagEvaluationSet.class);
        if (set.queries().isEmpty()) {
            log.warn("RAG evaluation skipped: empty evaluation set at {}", evaluationSetPath);
            return;
        }

        log.info("======== RAG evaluation started (kbId={}, queries={}, topK={}) ========",
                set.kbId(), set.queries().size(), set.topK());

        if (skipRewrite) {
            // 不依赖 chat 模型的消融列：noRewrite(rerank on/off) + 生产阈值
            EvaluationReport rerankOnly = ragEvaluator.evaluate(set, false, true);
            EvaluationReport minimal = ragEvaluator.evaluate(set, false, false);
            log.info("[ablation-skip-rewrite] rewrite=false rerank=true | {}", rerankOnly.compactSummary());
            log.info("[ablation-skip-rewrite] rewrite=false rerank=false | {}", minimal.compactSummary());

            EvaluationReport withThreshold =
                    ragEvaluator.evaluate(set, false, true, productionScoreThreshold);
            log.info("[ablation-threshold] productionScoreThreshold={} rewrite=false rerank=true | {}",
                    productionScoreThreshold, withThreshold.compactSummary());

            log.info("-- per-query detail (rerank on, no rewrite) --");
            for (QueryEvaluation query : rerankOnly.queries()) {
                log.info("[query] Q: {} | expected={} | retrieved={} | recall={} precision={}",
                        query.query().question(),
                        query.query().expectedDocuments(),
                        query.retrievedDocuments(),
                        String.format("%.3f", query.metrics().recallAtK()),
                        String.format("%.3f", query.metrics().precisionAtK()));
            }

            if (semanticEnabled) {
                runSemanticEvaluation(rerankOnly.queries());
            }

            log.info("======== RAG evaluation finished (skip-rewrite) ========");
            return;
        }

        EvaluationReport full = ragEvaluator.evaluate(set, true, true);
        EvaluationReport noRewrite = ragEvaluator.evaluate(set, false, true);
        EvaluationReport noRerank = ragEvaluator.evaluate(set, true, false);
        EvaluationReport minimal = ragEvaluator.evaluate(set, false, false);

        log.info("[ablation] {}", full.compactSummary());
        log.info("[ablation] {}", noRewrite.compactSummary());
        log.info("[ablation] {}", noRerank.compactSummary());
        log.info("[ablation] {}", minimal.compactSummary());

        // 第五列：与 full 相同的开关组合，但叠加生产路径的真实阈值。
        // 生产环境所有检索都会经过这道过滤，若阈值会吞掉正确答案，只有这一列能暴露。
        EvaluationReport withProductionThreshold =
                ragEvaluator.evaluate(set, true, true, productionScoreThreshold);
        log.info("[ablation-threshold] productionScoreThreshold={} {}", productionScoreThreshold,
                withProductionThreshold.compactSummary());

        log.info("-- per-query detail (baseline: rewrite+rerank) --");
        for (QueryEvaluation query : full.queries()) {
            log.info("[query] Q: {} | expected={} | retrieved={} | recall={} precision={}",
                    query.query().question(),
                    query.query().expectedDocuments(),
                    query.retrievedDocuments(),
                    String.format("%.3f", query.metrics().recallAtK()),
                    String.format("%.3f", query.metrics().precisionAtK()));
        }

        if (semanticEnabled) {
            runSemanticEvaluation(full.queries());
        }

        log.info("======== RAG evaluation finished ========");
    }

    private void runSemanticEvaluation(List<QueryEvaluation> queries) {
        log.info("-- semantic evaluation (LLM-as-judge) --");
        List<SemanticMetrics> allMetrics = new ArrayList<>();
        for (QueryEvaluation query : queries) {
            List<String> chunkTexts = query.chunkTexts();
            if (chunkTexts.isEmpty()) {
                log.warn("[semantic] Q: {} | skipped (no retrieved chunks)", query.query().question());
                continue;
            }
            SemanticMetrics metrics = semanticEvaluator.evaluate(query.query().question(), chunkTexts);
            allMetrics.add(metrics);
            log.info("[semantic] Q: {} | faithfulness={} answerRelevancy={}",
                    query.query().question(),
                    String.format("%.3f", metrics.faithfulness()),
                    String.format("%.3f", metrics.answerRelevancy()));
        }
        if (!allMetrics.isEmpty()) {
            double meanFaithfulness = allMetrics.stream().mapToDouble(SemanticMetrics::faithfulness).average().orElse(0.0);
            double meanRelevancy = allMetrics.stream().mapToDouble(SemanticMetrics::answerRelevancy).average().orElse(0.0);
            log.info("[semantic] summary | mean faithfulness={} mean answerRelevancy={}",
                    String.format("%.3f", meanFaithfulness),
                    String.format("%.3f", meanRelevancy));
        }
    }
}
