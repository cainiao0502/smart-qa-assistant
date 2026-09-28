package com.nailinai.ragent.chat.retrieve;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词检索的统一分词器：英文/数字词整词保留，中文片段切 2-gram。
 *
 * <p>独立成静态工具类的原因：分词结果是<strong>入库（chunk_tokens 列）与查询
 * （tsquery 构造）两侧的契约</strong>——两侧必须用同一实现，否则 tsv 索引静默失配、
 * 关键词通道召回归零。历史上曾直接把整句丢给 plainto_tsquery('simple', ...)，
 * PG 的 simple 分词器按空格切分，中文整段成为单个超长 token 无法命中任何 chunk，
 * 才演进出本应用层分词。任何一侧改动分词逻辑都必须同时重建索引并回填。</p>
 *
 * <p><b>限量只属于查询侧。</b>历史上的 MAX_TOKENS=24 同时作用于入库与查询两侧，
 * 后果是 500 字切片只有前 ~25 个字符进入 tsv 索引（24 个 2-gram），切片尾部的
 * 术语在关键词通道物理不可见——覆盖率 5.8%，通道越「准」越只在头部命中。
 * 现在 {@link #tokenize(String)} 全量产出（入库侧用它），仅查询侧在
 * {@link #toTsQueryOr(String)} 里先去重再截断到 {@value #MAX_QUERY_TOKENS}。</p>
 */
public final class KeywordTokenizer {

    /** 英文/数字/下划线/点整词，或连续汉字片段 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_.]+|[\\u4e00-\\u9fff]+");

    /**
     * 单次查询参与 tsquery 的最大 token 数。查询文本短，正常远达不到该值；
     * 只防异常超长输入撑爆 tsquery。<strong>入库侧不限量</strong>，见类注释。
     */
    public static final int MAX_QUERY_TOKENS = 24;

    private KeywordTokenizer() {
    }

    /**
     * 分词（全量，不限量）：英文/数字词整词保留并小写化，中文片段切 2-gram，
     * 去重后保持首次出现顺序。入库侧（chunk_tokens）使用全量结果，
     * 保证切片任意位置的术语都在 tsv 索引里。
     */
    public static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String part = matcher.group().toLowerCase(Locale.ROOT);
            boolean isChinese = part.codePoints().allMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
            if (isChinese && part.length() > 1) {
                for (int i = 0; i + 2 <= part.length(); i++) {
                    tokens.add(part.substring(i, i + 2));
                }
            } else {
                tokens.add(part);
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 分词结果转成空格分隔的 token 串（写入 document_chunk.chunk_tokens）。
     * 该串经 to_tsvector('simple', ...) 按空格重新切词进索引。全量不限量。
     */
    public static String toTokenString(String text) {
        return String.join(" ", tokenize(text));
    }

    /**
     * 分词结果转成 tsquery 字面量（查询侧）：每个 token 加单引号防保留字，
     * 以 OR 连接——命中任一 token 即召回，评分交给 ts_rank。
     * 先去重（tokenize 内建）再截断到 {@value #MAX_QUERY_TOKENS}，防超长查询撑爆 tsquery。
     * token 来自本类的白名单正则，不含引号，拼接是注入安全的。
     */
    public static String toTsQueryOr(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return null;
        }
        if (tokens.size() > MAX_QUERY_TOKENS) {
            tokens = tokens.subList(0, MAX_QUERY_TOKENS);
        }
        StringBuilder tsQuery = new StringBuilder();
        for (String token : tokens) {
            if (tsQuery.length() > 0) {
                tsQuery.append(" | ");
            }
            tsQuery.append('\'').append(token).append('\'');
        }
        return tsQuery.toString();
    }
}
