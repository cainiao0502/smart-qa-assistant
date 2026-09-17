package com.nailinai.ragent.chat.retrieve;

import java.util.List;

public interface SearchChannel {

    String name();

    List<SearchResult> search(SearchRequest request);
}