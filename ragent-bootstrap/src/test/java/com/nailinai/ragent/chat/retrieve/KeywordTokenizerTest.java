package com.nailinai.ragent.chat.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeywordTokenizer 分词契约的单元测试。
 *
 * <p>该分词是入库（chunk_tokens/tsv）与查询（tsquery）两侧的共享契约：
 * 这里锁死英文整词、中文 2-gram、去重、以及「限量只属于查询侧」四个行为。
 * 任何一侧改动分词逻辑导致此处失败时，必须同步重建索引并回填。</p>
 *
 * <p>历史教训（E1）：旧断言 {@code hasSize(MAX_TOKENS)} 把「入库也截断到 24」
 * 锁成了规格——500 字切片只有前 ~25 字进 tsv 索引，148 个单测全绿而缺陷就在
 * 断言里。现在的断言反过来：入库侧<strong>必须</strong>全量，查询侧才限量。</p>
 */
class KeywordTokenizerTest {

    @Test
    @DisplayName("英文与数字整词保留并小写化")
    void englishWords_shouldBeKeptWhole() {
        List<String> tokens = KeywordTokenizer.tokenize("HNSW ef_search v3.2");

        assertThat(tokens).containsExactly("hnsw", "ef_search", "v3.2");
    }

    @Test
    @DisplayName("中文片段切 2-gram")
    void chinese_shouldBeSplitIntoBigrams() {
        List<String> tokens = KeywordTokenizer.tokenize("重排序模型");

        assertThat(tokens).containsExactly("重排", "排序", "序模", "模型");
    }

    @Test
    @DisplayName("中英混合：英文整词、中文 2-gram，互不污染")
    void mixed_shouldTreatBothCorrectly() {
        List<String> tokens = KeywordTokenizer.tokenize("RAG 检索增强生成");

        assertThat(tokens).contains("rag");
        assertThat(tokens).containsSubsequence("检索", "索增", "增强");
        assertThat(tokens).noneMatch(token -> token.length() > 2 && token.matches(".*[\\u4e00-\\u9fff].*"));
    }

    @Test
    @DisplayName("去重且入库侧不限量：30 个不同汉字 → 29 个 2-gram 全部产出")
    void tokenize_shouldDedupeWithoutCap() {
        // 30 个互不相同的汉字 → 29 个互不重复的 2-gram。旧实现截断到 24，
        // 把缺陷锁进断言（hasSize(MAX_TOKENS)）；入库侧必须全量——
        // 否则切片尾部术语物理不在 tsv 索引里，关键词通道对尾部内容不可见。
        StringBuilder distinct = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            distinct.append((char) (0x4E00 + i));
        }
        List<String> tokens = KeywordTokenizer.tokenize(distinct.toString());

        assertThat(tokens).hasSize(29);
        assertThat(tokens).doesNotHaveDuplicates();

        // 重复内容去重：2-gram 只有「测试」「试测」两种
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            repeated.append("测试");
        }
        assertThat(KeywordTokenizer.tokenize(repeated.toString()))
                .containsExactly("测试", "试测");
    }

    @Test
    @DisplayName("覆盖率回归：切片尾部的术语必须进 token 串（旧缺陷：第 25 字之后不可见）")
    void longChunk_tailTerms_shouldBeIndexed() {
        // 60 个填充汉字（旧实现下 24 个 2-gram 只覆盖前 ~25 字），
        // 之后紧跟术语「量子退火」——它必须出现在入库 token 串里
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            chunk.append((char) (0x4E00 + i));
        }
        chunk.append("量子退火");

        String tokenString = KeywordTokenizer.toTokenString(chunk.toString());

        assertThat(tokenString).contains("量子", "子退", "退火");
    }

    @Test
    @DisplayName("查询侧先去重再截断 MAX_QUERY_TOKENS：超长查询限量防撑爆 tsquery")
    void toTsQueryOr_shouldDedupeThenCap() {
        StringBuilder distinct = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            distinct.append((char) (0x4E00 + i));
        }

        String tsQuery = KeywordTokenizer.toTsQueryOr(distinct.toString());

        // 40 字 → 39 个去重后的 2-gram，查询侧截断到 24
        assertThat(tsQuery.split(" \\| ")).hasSize(KeywordTokenizer.MAX_QUERY_TOKENS);
    }

    @Test
    @DisplayName("tsquery 构造：token 加引号并以 OR 连接；空输入返回 null")
    void toTsQueryOr_shouldQuoteAndJoin() {
        assertThat(KeywordTokenizer.toTsQueryOr("重排 rag")).isEqualTo("'重排' | 'rag'");
        assertThat(KeywordTokenizer.toTsQueryOr("  ")).isNull();
        assertThat(KeywordTokenizer.toTsQueryOr(null)).isNull();
    }

    @Test
    @DisplayName("toTokenString 输出空格分隔的 token 串，与 tokenize 一致")
    void toTokenString_shouldMatchTokenize() {
        String tokenString = KeywordTokenizer.toTokenString("重排序模型 hnsw");

        assertThat(tokenString).isEqualTo("重排 排序 序模 模型 hnsw");
    }
}
