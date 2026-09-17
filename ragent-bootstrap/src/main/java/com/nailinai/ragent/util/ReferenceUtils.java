package com.nailinai.ragent.util;

import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.entity.DocumentChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReferenceUtils {

    private ReferenceUtils() {
    }

    public static List<ReferenceChunkResponse> mergeReferences(List<DocumentChunk> baseChunks,
                                                                List<ReferenceChunkResponse> extraReferences) {
        Map<String, ReferenceChunkResponse> merged = new LinkedHashMap<>();
        for (DocumentChunk chunk : baseChunks) {
            ReferenceChunkResponse reference = ReferenceChunkResponse.builder()
                    .docId(chunk.getDocId())
                    .documentName(chunk.getDocumentName())
                    .fileType(chunk.getFileType())
                    .chunkIndex(chunk.getChunkIndex())
                    .paragraphIndex(chunk.getParagraphIndex())
                    .chunkText(chunk.getChunkText())
                    .score(chunk.getScore())
                    .distance(chunk.getDistance())
                    .rerankScore(chunk.getRerankScore())
                    .hitReason(chunk.getHitReason())
                    .build();
            merged.put(referenceKey(reference), reference);
        }
        for (ReferenceChunkResponse reference : extraReferences) {
            merged.putIfAbsent(referenceKey(reference), reference);
        }
        return new ArrayList<>(merged.values());
    }

    private static String referenceKey(ReferenceChunkResponse reference) {
        return "%s:%s".formatted(reference.getDocId(), reference.getChunkIndex());
    }
}
