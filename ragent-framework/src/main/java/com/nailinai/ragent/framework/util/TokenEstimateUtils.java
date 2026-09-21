package com.nailinai.ragent.framework.util;

/**
 * 粗粒度 token 估算。
 *
 * <p>历史缺陷：旧实现统一按 {@code length / 4} 估算，这是纯英文经验值。
 * 中文在主流 BPE 分词器下约 0.6~1 token/字符，按 /4 估算会系统性低估 2~4 倍，
 * 导致 ContextWindowManager 的历史裁剪在中文对话里形同虚设（预算 6000 实际可达 1.5万+ token）。
 *
 * <p>本实现按字符类别分别估算：CJK 字符按 1 token/字符（略偏高，宁可多裁不少留），
 * 其他字符按 4 字符/token。仍非真实 tokenizer，但对中英混合文本的偏差
 * 从「数倍低估」收敛到「±30% 以内」，足够做预算裁剪。
 */
public final class TokenEstimateUtils {

    private TokenEstimateUtils() {
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return Math.max(1, cjk + other / 4);
    }

    /** 是否为 CJK 表意文字/标点/全角字符（这些字符在 BPE 下几乎每个都占至少 1 token） */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)   // CJK 统一表意文字
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)   // CJK 扩展 A
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)   // CJK 兼容表意文字
                || (codePoint >= 0x3000 && codePoint <= 0x303F)   // CJK 标点
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);  // 全角字符
    }
}
