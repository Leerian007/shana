package com.shana.common.api;

import java.util.Objects;

/**
 * 统一 API 响应结构。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": {}
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.message());
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new Result<>(errorCode.code(), message, null);
    }
}
