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

    /** 泛型版失败响应：失败分支与成功分支在同一返回类型的方法里使用（data 恒为 null） */
    public static <T> Result<T> failureOf(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.name(), message, null);
    }
}