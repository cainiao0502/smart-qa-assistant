package com.nailinai.ragent.framework.util;

public final class TokenEstimateUtils {

    private TokenEstimateUtils() {
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}