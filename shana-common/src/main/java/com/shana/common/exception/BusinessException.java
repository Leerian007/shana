package com.shana.common.exception;

import com.shana.common.api.ErrorCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常：表示"可预期的失败"，由全局异常处理器翻译成统一响应。
 * <p>不要在 catch 中包装未知异常后原样抛出，非预期异常应交给 {@link RuntimeException} 分支处理。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
