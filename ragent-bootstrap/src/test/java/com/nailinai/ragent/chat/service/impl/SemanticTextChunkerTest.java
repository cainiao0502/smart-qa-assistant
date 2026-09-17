package com.nailinai.ragent.chat.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义切分器测试。
 *
 * <p>重点是**不得把非句末的英文句点拆开**：早期实现把 {@code .} 一律当作句子结束符，
 * 导致 {@code 0.60} 被切成 {@code "0. 60"}、{@code chunk.size} 被切成
 * {@code "chunk. size"}，直接破坏了检索的词面匹配与向量表示。</p>
 */
class SemanticTextChunkerTest {

    private final SemanticTextChunker chunker = new SemanticTextChunker(800, 120);

    private String joined(String content) {
        return String.join("\n", chunker.split(content));
    }

    @Test
    @DisplayName("小数点不被拆散：0.60 不应变成 \"0. 60\"")
    void decimalPoint_shouldNotBeSplit() {
        String text = joined("向量相似度下限为 0.60，低于该值不进入上下文。");
        assertThat(text).contains("0.60");
        assertThat(text).doesNotContain("0. 60");
    }

    @Test
    @DisplayName("点号标识符不被拆散：chunk.size / hnsw.ef_search")
    void dottedIdentifier_shouldNotBeSplit() {
        String text = joined("参数 chunk.size 为 800；hnsw.ef_search 为 64。");
        assertThat(text).contains("chunk.size");
        assertThat(text).contains("hnsw.ef_search");
        assertThat(text).doesNotContain("chunk. size");
        assertThat(text).doesNotContain("hnsw. ef_search");
    }

    @Test
    @DisplayName("版本号不被拆散：v3.2 不应变成 \"v3. 2\"")
    void versionNumber_shouldNotBeSplit() {
        String text = joined("当前文档版本 v3.2，上一版为 v3.1。");
        assertThat(text).contains("v3.2");
        assertThat(text).contains("v3.1");
        assertThat(text).doesNotContain("v3. 2");
    }

    @Test
    @DisplayName("多级标识符与文件名不被拆散")
    void multiLevelIdentifier_shouldNotBeSplit() {
        String text = joined("配置文件 application.yml 中的 app.rag.chunk-size 控制切片大小。");
        assertThat(text).contains("application.yml");
        assertThat(text).contains("app.rag.chunk-size");
        assertThat(text).doesNotContain("application. yml");
    }

    @Test
    @DisplayName("英文句末（点后接空格）仍然正常断句")
    void englishSentenceEnd_shouldStillBreak() {
        List<String> chunks = chunker.split("First sentence. Second sentence.");
        assertThat(chunks).isNotEmpty();
        assertThat(String.join(" ", chunks)).contains("First sentence.");
    }

    @Test
    @DisplayName("中文句号与问号正常断句且内容完整")
    void chineseSentenceEnd_shouldBreak() {
        String text = joined("第一句话。第二句话？第三句话！");
        assertThat(text).contains("第一句话");
        assertThat(text).contains("第二句话");
        assertThat(text).contains("第三句话");
    }

    @Test
    @DisplayName("空内容与 null 返回空列表")
    void blankContent_shouldReturnEmpty() {
        assertThat(chunker.split(null)).isEmpty();
        assertThat(chunker.split("   ")).isEmpty();
    }

    @Test
    @DisplayName("超长文本按 chunkSize 切分且保留重叠")
    void longContent_shouldSplitWithOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是第").append(i).append("句话。");
        }
        List<String> chunks = chunker.split(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }
}
