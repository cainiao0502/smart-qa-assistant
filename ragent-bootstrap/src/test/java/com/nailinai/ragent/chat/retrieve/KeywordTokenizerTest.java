package com.nailinai.ragent.chat.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeywordTokenizer 分词契约的单元测试。
 *
 * <p>该分词是入库（chunk_tokens/tsv）与查询（tsquery）两侧的共享契约：
 * 这里锁死英文整词、中文 2-gram、去重限量三个行为，任何一侧改动分词逻辑
 * 导致此处失败时，必须同步重建索引并回填。</p>
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
    @DisplayName("去重且限量 MAX_TOKENS")
    void shouldDedupeAndCapTokens() {
        // 30 个互不相同的汉字 → 29 个互不重复的 2-gram，验证被截到 MAX_TOKENS
        StringBuilder distinct = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            distinct.append((char) (0x4E00 + i));
        }
        List<String> tokens = KeywordTokenizer.tokenize(distinct.toString());

        assertThat(tokens).hasSize(KeywordTokenizer.MAX_TOKENS);
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
