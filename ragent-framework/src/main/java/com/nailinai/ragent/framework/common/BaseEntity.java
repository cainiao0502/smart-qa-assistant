package com.nailinai.ragent.framework.common;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BaseEntity {

    private Long id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}