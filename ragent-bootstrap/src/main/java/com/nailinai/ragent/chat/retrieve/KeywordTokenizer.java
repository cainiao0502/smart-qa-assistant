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
 */
public final class KeywordTokenizer {

    /** 英文/数字/下划线/点整词，或连续汉字片段 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_.]+|[\\u4e00-\\u9fff]+");

    /** 单次查询/单条文档参与检索的最大 token 数，防止超长文本撑爆 tsquery */
    public static final int MAX_TOKENS = 24;

    private KeywordTokenizer() {
    }

    /**
     * 分词：英文/数字词整词保留并小写化，中文片段切 2-gram，去重后限量
     * {@value #MAX_TOKENS} 个。
     */
    public static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String part = matcher.group().toLowerCase(Locale.ROOT);
            boolean isChinese = part.codePoints().allMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
            if (isChinese && part.length() > 1) {
                for (int i = 0; i + 2 <= part.length() && tokens.size() < MAX_TOKENS; i++) {
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
     * 该串经 to_tsvector('simple', ...) 按空格重新切词进索引。
     */
    public static String toTokenString(String text) {
        return String.join(" ", tokenize(text));
    }

    /**
     * 分词结果转成 tsquery 字面量（查询侧）：每个 token 加单引号防保留字，
     * 以 OR 连接——命中任一 token 即召回，评分交给 ts_rank。
     * token 来自本类的白名单正则，不含引号，拼接是注入安全的。
     */
    public static String toTsQueryOr(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return null;
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
