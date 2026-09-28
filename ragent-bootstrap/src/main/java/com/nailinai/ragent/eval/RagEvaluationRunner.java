package com.nailinai.ragent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nailinai.ragent.user.context.UserIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.EmptyResultDataAccessException;
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
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

    /**
     * 评估门禁（E1）：&gt;0 时基线列 recall 低于该值即抛异常使启动失败——
     * CI 评估任务据此以非零退出码「亮红灯」。0 = 关闭门禁，只出报告。
     */
    private final double minRecall;

    /** CI 专用：评估（含门禁）结束后让进程以 0 退出，否则服务器会照常起来挂着（本地保持 false） */
    private final boolean exitAfterRun;

    public RagEvaluationRunner(RagEvaluator ragEvaluator,
                               RagSemanticEvaluator semanticEvaluator,
                               ObjectMapper objectMapper,
                               org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                               @Value("${app.eval.enabled:false}") boolean enabled,
                               @Value("${app.eval.semantic-enabled:false}") boolean semanticEnabled,
                               @Value("${app.eval.set-path:data/eval/eval-set.json}") String evaluationSetPath,
                               @Value("${app.rag.reference-distance-threshold:0.4}") double referenceDistanceThreshold,
                               @Value("${app.eval.skip-rewrite:false}") boolean skipRewrite,
                               @Value("${app.eval.min-recall:0.0}") double minRecall,
                               @Value("${app.eval.exit-after-run:false}") boolean exitAfterRun) {
        this.ragEvaluator = ragEvaluator;
        this.semanticEvaluator = semanticEvaluator;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.semanticEnabled = semanticEnabled;
        this.evaluationSetPath = evaluationSetPath;
        this.productionScoreThreshold = Math.max(0.0, Math.min(1.0, 1 - referenceDistanceThreshold));
        this.skipRewrite = skipRewrite;
        this.minRecall = Math.max(0.0, minRecall);
        this.exitAfterRun = exitAfterRun;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            // 评估默认关闭（app.eval.enabled:false），常规启动每次都会走到这里。
            // 因此这里**不能**无条件打 ERROR——否则「每次正常启动都有一条 ERROR」会成为
            // 常态，等于训练所有人忽略 ERROR 日志。只有 CI 门禁模式（exit-after-run=true）
            // 下「门禁没启用」才是需要亮红灯的故障；本地模式下 finish(1) 本身也是 no-op。
            if (exitAfterRun) {
                log.error("RAG evaluation aborted: app.eval.enabled=false，评估门禁未启用");
            }
            finish(1);
            return;
        }
        File file = new File(evaluationSetPath);
        if (!file.isFile()) {
            log.error("RAG evaluation aborted: evaluation set not found at {}", evaluationSetPath);
            finish(1);
            return;
        }
        RagEvaluationSet set = objectMapper.readValue(file, RagEvaluationSet.class);
        if (set.queries().isEmpty()) {
            log.error("RAG evaluation aborted: empty evaluation set at {}", evaluationSetPath);
            finish(1);
            return;
        }
        if (!evaluationSetInSyncWithDb(set)) {
            // 评估集与库脱节时继续跑只会得到全 0 的无效报告，还白白消耗 embedding 调用——
            // 直接中止并给出明确的修复指引（真机教训：aaa.txt 引用的一批文档已被重建的库清空）
            log.error("RAG evaluation aborted: 评估集引用的文档在库中一个都不存在（评估集与库脱节）。"
                    + "请核对 kbId={} 下的文档名并同步 data/eval/eval-set.json", set.kbId());
            finish(1);
            return;
        }

        // 检索归属 fail-closed：SearchRequest.of 拒绝 null owner。评估跑在启动主线程，
        // 没有任何登录上下文，必须显式解析库 owner 并绑定到 UserIdHolder——
        // 否则整轮评估会在 SearchRequest.of 处 fail-fast，或（更糟）被逐条 catch 伪装成
        // 「32 条查询全部基础设施故障」的全零报告。
        Long ownerUserId = resolveKbOwnerUserId(set.kbId());
        if (ownerUserId == null) {
            log.error("RAG evaluation aborted: kbId={} 不存在或缺少 owner_user_id，"
                    + "无法绑定检索归属（fail-closed 契约要求 owner 非空）", set.kbId());
            finish(1);
            return;
        }

        boolean aborted = false;
        UserIdHolder.set(ownerUserId);
        try {
            log.info("======== RAG evaluation started (kbId={}, queries={}, topK={}, ownerUserId={}) ========",
                    set.kbId(), set.queries().size(), set.topK(), ownerUserId);

            if (skipRewrite) {
                runSkipRewriteAblation(set);
                log.info("======== RAG evaluation finished (skip-rewrite) ========");
            } else {
                runFullAblation(set);
                log.info("======== RAG evaluation finished ========");
            }
        } catch (EvaluationAbortedException ex) {
            // 连续失败 tripwire：系统性故障必须整轮中止并 error 级留痕，
            // 不能顺着「单条失败只跳过」的容错漏斗滑成全零报告。
            aborted = true;
            log.error("RAG evaluation aborted: {}", ex.getMessage());
        } finally {
            UserIdHolder.clear();
        }

        finish(aborted ? 1 : 0);
    }

    /**
     * 未被评估正常完成的出口统一走这里：CI 门禁模式（exit-after-run=true）下必须非零退出。
     *
     * <p><b>为什么所有出口都要收敛到这一处：</b>{@code eval.yml} 以
     * {@code -Dspring.main.web-application-type=none} 跑非 web 应用，Runner 一返回进程就
     * 自然退出、退出码 0 —— 于是任何「评估没跑成」的提前 return 都会被 CI 读成绿灯，
     * 门禁形同虚设（09-16 教训「403 被读成命中 0 条」的进程级重演）。</p>
     *
     * <p>三条路径的分工：tripwire 中止 → {@code finish(1)}；门禁阈值失败（min-recall）
     * → 异常直接穿透使启动失败，不经过这里；只有真正跑完 → {@code finish(0)}。</p>
     */
    private void finish(int status) {
        if (exitAfterRun) {
            exitGate(status);
        }
    }

    /** 进程退出出口：独立成方法供门禁单测拦截验证（真实实现才真正 System.exit） */
    void exitGate(int status) {
        log.info("RAG evaluation exit-after-run — process exiting with status {}", status);
        System.exit(status);
    }

    /** skip-rewrite 降级模式：只跑不依赖 chat 模型的消融列（noRewrite / minimal / 生产阈值） */
    private void runSkipRewriteAblation(RagEvaluationSet set) {
        // 不依赖 chat 模型的消融列：noRewrite(rerank on/off) + 生产阈值
        EvaluationReport rerankOnly = ragEvaluator.evaluate(set, false, true);
        EvaluationReport minimal = ragEvaluator.evaluate(set, false, false);
        log.info("[ablation-skip-rewrite] rewrite=false rerank=true | {}", rerankOnly.compactSummary());
        log.info("[ablation-skip-rewrite] rewrite=false rerank=false | {}", minimal.compactSummary());
        checkRecallGate(rerankOnly);

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
    }

    /** 全量 2x2 消融 + 生产阈值第五列 + 逐查询明细 */
    private void runFullAblation(RagEvaluationSet set) {
        EvaluationReport full = ragEvaluator.evaluate(set, true, true);
        EvaluationReport noRewrite = ragEvaluator.evaluate(set, false, true);
        EvaluationReport noRerank = ragEvaluator.evaluate(set, true, false);
        EvaluationReport minimal = ragEvaluator.evaluate(set, false, false);

        log.info("[ablation] {}", full.compactSummary());
        log.info("[ablation] {}", noRewrite.compactSummary());
        log.info("[ablation] {}", noRerank.compactSummary());
        log.info("[ablation] {}", minimal.compactSummary());
        checkRecallGate(full);

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
    }

    /** 评估门禁：基线列 recall 低于 min-recall 即抛异常（ApplicationRunner 异常 = 进程非零退出） */
    private void checkRecallGate(EvaluationReport baseline) {
        if (minRecall <= 0) {
            return;
        }
        double recall = baseline.summary().recallAtK();
        if (recall < minRecall) {
            throw new IllegalStateException(String.format(
                    "评估门禁失败：基线 recall@k=%.3f 低于门禁阈值 %.3f。"
                            + "先跑消融定位退步环节（重排/阈值/检索通道），不要盲目合并。",
                    recall, minRecall));
        }
        log.info("评估门禁通过：基线 recall@k={} >= {}", String.format("%.3f", recall), minRecall);
    }

    /**
     * 解析知识库归属用户：评估/调试等离线路径没有登录上下文，
     * owner 一律以「库的登记归属」为准（而非调用者身份），kbId 不存在返回 null。
     */
    private Long resolveKbOwnerUserId(Long kbId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT owner_user_id FROM knowledge_base WHERE id = ?", Long.class, kbId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 评估集健全性检查：期望文档名与库内文档名（contains 匹配，容忍 UUID 前缀）
     * 至少命中一个才算同步；全不命中即视为脱节。
     */
    private boolean evaluationSetInSyncWithDb(RagEvaluationSet set) {
        List<String> expectedNames = set.queries().stream()
                .flatMap(query -> query.expectedDocuments().stream())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (expectedNames.isEmpty()) {
            return true;
        }
        List<String> dbNames = jdbcTemplate.queryForList(
                "SELECT name FROM document WHERE kb_id = ?", String.class, set.kbId());
        boolean anyMatched = expectedNames.stream()
                .anyMatch(expected -> dbNames.stream()
                        .anyMatch(name -> name != null
                                && name.toLowerCase().contains(expected.trim().toLowerCase())));
        if (!anyMatched) {
            log.error("expected={} but kbId={} documents={}", expectedNames, set.kbId(), dbNames);
        }
        return anyMatched;
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
