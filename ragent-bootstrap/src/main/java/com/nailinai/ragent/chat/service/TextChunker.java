package com.nailinai.ragent.chat.service;

import java.util.List;

public interface TextChunker {

    List<String> split(String content);
}
