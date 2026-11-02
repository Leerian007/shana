package com.shana.common.api;

/**
 * 业务错误码。
 * <p>{@code code} 与 HTTP 状态码保持一致，前端只需判断一处即可，避免"HTTP 200 + 业务码"带来的双重判断。
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(200, "success"),

    /** 请求参数校验失败 */
    BAD_REQUEST(400, "请求参数不合法"),

    /** 请求的资源不存在 */
    NOT_FOUND(404, "请求的资源不存在"),

    /** 未预期的系统异常 */
    INTERNAL_ERROR(500, "系统内部错误"),

    /** 依赖的外部能力不可用（例如尚无可用模型适配器） */
    SERVICE_UNAVAILABLE(503, "服务暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
