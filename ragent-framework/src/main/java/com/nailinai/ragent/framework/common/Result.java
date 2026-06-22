package com.nailinai.ragent.framework.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private String code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.name(), "success", data);
    }

    public static Result<Void> success() {
        return new Result<>(ErrorCode.SUCCESS.name(), "success", null);
    }

    public static Result<Void> failure(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.name(), message, null);
    }
}