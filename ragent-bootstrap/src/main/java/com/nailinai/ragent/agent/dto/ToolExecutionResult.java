package com.nailinai.ragent.agent.dto;

import com.nailinai.ragent.dto.response.ReferenceChunkResponse;
import com.nailinai.ragent.dto.response.ToolCallTraceResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ToolExecutionResult {

    private ToolCallTraceResponse trace;
    private String summary;
    private String supplementalContext;
    private List<ReferenceChunkResponse> references;
    private Object rawResult;
    private String observation;
}
