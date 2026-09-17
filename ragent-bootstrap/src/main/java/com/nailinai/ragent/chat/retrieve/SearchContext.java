package com.nailinai.ragent.chat.retrieve;

public record SearchContext(
        String originalQuery,
        String effectiveQuery,
        int topK,
        double scoreThreshold
) {
    public static SearchContext of(String originalQuery,
                                   String effectiveQuery,
                                   int topK,
                                   double scoreThreshold) {
        return new SearchContext(originalQuery, effectiveQuery, topK, scoreThreshold);
    }
}