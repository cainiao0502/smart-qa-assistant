package com.nailinai.ragent.user.entity;

import com.nailinai.ragent.framework.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class User extends BaseEntity {

    private String username;
    private String passwordHash;
    private String role;
}