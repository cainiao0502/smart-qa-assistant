package com.nailinai.ragent.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import com.nailinai.ragent.enums.DocumentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Document extends BaseEntity {

    private Long kbId;
    private String name;
    private String fileType;
    private String storagePath;
    private String content;
    private DocumentStatus status;
    private String errorMessage;
    private Long chunkCount;
}
