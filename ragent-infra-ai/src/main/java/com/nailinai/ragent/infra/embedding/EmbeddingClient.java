package com.nailinai.ragent.infra.embedding;

import java.util.List;

public interface EmbeddingClient {

    String name();

    List<Float> embed(String text);

    List<List<Float>> embedBatch(List<String> texts);
}