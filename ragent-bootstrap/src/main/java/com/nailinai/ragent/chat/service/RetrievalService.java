package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.response.RetrievalResult;

public interface RetrievalService {

    RetrievalResult retrieve(ChatRequest request, int defaultTopK, double defaultScoreThreshold);
}
