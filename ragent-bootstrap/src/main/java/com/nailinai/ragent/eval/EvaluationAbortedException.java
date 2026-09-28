package com.nailinai.ragent.eval;

/**
 * 评估因「连续多条查询失败」被中止——系统性故障的显式信号，区别于单条查询的
 * 供应商抖动（后者只跳过该条）。
 *
 * <p>动机（09-16 教训）：权限/配置类故障若被逐条吞掉，评估报告会呈现为
 * 「召回全零」，权限拦截被误读成检索能力崩塌。宁可整轮中止并留下 error 级日志，
 * 也不产出一张看似有效实则全零的报告。</p>
 */
public class EvaluationAbortedException extends RuntimeException {

    public EvaluationAbortedException(String message) {
        super(message);
    }
}
