package com.nailinai.ragent.util;

import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答中引用真实性的校验器。
 *
 * <p>system prompt 只「要求」模型引用来源（文档名 + 片段号），但 LLM 完全可能编造
 * 形如「（来源：xxx.pdf，片段#99）」的引用——references 面板里根本不存在这个切片，
 * 用户却会把它当成可信出处。校验器在最终回答上做一次后处理：</p>
 *
 * <ul>
 *   <li>提取所有「片段#N」式引用；</li>
 *   <li>在引用前方小窗口内寻找归属文档名（取最靠近的命中）；</li>
 *   <li>文档存在但片段号不存在 → 只移除片段号（保留文档名）；</li>
 *   <li>无法归属到任何已知文档 → 保守不动（宁可漏报，不可错改正文）。</li>
 * </ul>
 *
 * <p>设计原则：这是尽力而为的净化，不是语义理解。正则不认识的引用格式原样保留，
 * 绝不会把真实引用改成错误内容。改动一律打 WARN 日志留痕。</p>
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    /** 片段号引用：兼容「片段#3」「片段 #3」「片段＃3」等写法 */
    private static final Pattern CHUNK_INDEX_PATTERN = Pattern.compile("片段\\s*[#＃]\\s*(\\d+)");

    /** 归属文档名的向前查找窗口：覆盖「来源：」「（」「，」等引用格式分隔文本 */
    private static final int DOC_NAME_LOOKBACK_CHARS = 60;

    /** 移除片段号时连同吞掉的紧邻分隔符 */
    private static final String SEPARATORS = "，,、；;：: \t";

    public String validateAndFix(String answer, List<ReferenceChunkResponse> references) {
        if (!StringUtils.hasText(answer) || references == null || references.isEmpty()) {
            return answer;
        }

        Map<String, Set<Integer>> validIndexesByDoc = new HashMap<>();
        Set<String> docNameKeys = new HashSet<>();
        for (ReferenceChunkResponse reference : references) {
            if (reference == null || !StringUtils.hasText(reference.getDocumentName())) {
                continue;
            }
            String key = reference.getDocumentName().trim().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            docNameKeys.add(key);
            if (reference.getChunkIndex() != null) {
                validIndexesByDoc.computeIfAbsent(key, k -> new HashSet<>()).add(reference.getChunkIndex());
            }
        }
        if (docNameKeys.isEmpty()) {
            return answer;
        }

        Matcher matcher = CHUNK_INDEX_PATTERN.matcher(answer);
        List<int[]> removals = new ArrayList<>();
        List<String> removedCitations = new ArrayList<>();
        while (matcher.find()) {
            String docKey = findDocNameBefore(answer, matcher.start(), docNameKeys);
            if (docKey == null) {
                // 无法归属到已知文档：可能是无关的普通文本，保守保留
                continue;
            }
            int chunkIndex;
            try {
                chunkIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (validIndexesByDoc.get(docKey).contains(chunkIndex)) {
                continue;
            }
            int start = matcher.start();
            while (start > 0 && SEPARATORS.indexOf(answer.charAt(start - 1)) >= 0) {
                start--;
            }
            int end = matcher.end();
            // 「（片段#9）」这类括号包裹：连同闭合括号一起移除，避免留下「（）」
            if (start > 0 && end < answer.length()
                    && isBracketOpen(answer.charAt(start - 1)) && isBracketClose(answer.charAt(end))) {
                start--;
                end++;
            }
            removals.add(new int[]{start, end});
            removedCitations.add(docKey + "#chunk" + chunkIndex);
        }

        if (removals.isEmpty()) {
            return answer;
        }

        StringBuilder fixed = new StringBuilder(answer.length());
        int cursor = 0;
        for (int[] range : removals) {
            fixed.append(answer, cursor, range[0]);
            cursor = range[1];
        }
        fixed.append(answer, cursor, answer.length());

        log.warn("Removed {} hallucinated citation(s) not present in references: {}",
                removedCitations.size(), String.join(", ", removedCitations));
        return fixed.toString();
    }

    /**
     * 在 {@code position} 前方窗口内寻找已知文档名，返回其小写 key。
     * 多个命中时取出现位置最靠近引用的（引用格式里文档名总在片段号紧前方）。
     */
    private String findDocNameBefore(String answer, int position, Set<String> docNameKeys) {
        int windowStart = Math.max(0, position - DOC_NAME_LOOKBACK_CHARS);
        String window = answer.substring(windowStart, position).toLowerCase(Locale.ROOT);
        String best = null;
        int bestEnd = -1;
        for (String docKey : docNameKeys) {
            int end = window.lastIndexOf(docKey);
            if (end >= 0 && end + docKey.length() > bestEnd) {
                bestEnd = end + docKey.length();
                best = docKey;
            }
        }
        return best;
    }

    private boolean isBracketOpen(char c) {
        return c == '（' || c == '(' || c == '【' || c == '[';
    }

    private boolean isBracketClose(char c) {
        return c == '）' || c == ')' || c == '】' || c == ']';
    }
}
