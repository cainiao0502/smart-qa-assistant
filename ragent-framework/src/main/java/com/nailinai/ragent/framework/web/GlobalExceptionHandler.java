package com.nailinai.ragent.framework.web;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.framework.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        return Result.failure(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("validation failed");
        log.warn("Validation exception: {}", message);
        return Result.failure(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLoginException(cn.dev33.satoken.exception.NotLoginException ex) {
        log.warn("Not login: type={}", ex.getType());
        return Result.failure(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotRoleException(cn.dev33.satoken.exception.NotRoleException ex) {
        log.warn("Not role: {}", ex.getRole());
        return Result.failure(ErrorCode.FORBIDDEN, "无权限访问");
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotPermissionException(cn.dev33.satoken.exception.NotPermissionException ex) {
        log.warn("Not permission: {}", ex.getCode());
        return Result.failure(ErrorCode.FORBIDDEN, "无权限访问");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return Result.failure(ErrorCode.INTERNAL_ERROR, ex.getMessage());
    }
}