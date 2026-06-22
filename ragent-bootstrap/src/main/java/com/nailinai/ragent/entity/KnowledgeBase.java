package com.nailinai.ragent.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeBase extends BaseEntity {

    private String name;
    private String description;
}
