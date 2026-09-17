package com.nailinai.ragent.user.dto;

import lombok.Data;

@Data
public class UserInfoResponse {

    private Long userId;
    private String username;
    private String role;
}