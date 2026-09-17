package com.nailinai.ragent.chat.retrieve;

import java.util.List;

public interface SearchPostProcessor {

    int order();

    List<SearchResult> process(List<SearchResult> inputs, SearchContext context);
}