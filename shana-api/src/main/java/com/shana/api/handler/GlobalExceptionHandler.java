package com.shana.api.handler;

import com.shana.common.api.ErrorCode;
import com.shana.common.api.Result;
import com.shana.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常翻译成统一响应结构。
 *
 * <p>处理顺序遵循"从具体到通用"，Spring 会选择最匹配的处理器，因此这里的方法顺序不影响结果。
 * <p>对外只暴露可理解的信息，未预期异常的真实堆栈只写日志，避免泄露实现细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期失败，按异常自带的错误码返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常 [{} {}] code={} message={}",
                request.getMethod(), request.getRequestURI(), ex.getErrorCode().code(), ex.getMessage());
        return build(ex.getErrorCode(), ex.getMessage());
    }

    /** @RequestBody 上的 Bean Validation 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), detail);
        return build(ErrorCode.BAD_REQUEST, detail);
    }

    /** 方法参数上的约束校验失败（例如 @Validated + @RequestParam）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), detail);
        return build(ErrorCode.BAD_REQUEST, detail);
    }

    /** 缺少必填的查询参数：不加此处理器会被兜底分支吞成 500，前端拿不到可读提示。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String detail = "缺少必填参数: " + ex.getParameterName();
        log.warn("缺少请求参数 [{} {}]: {}", request.getMethod(), request.getRequestURI(), detail);
        return build(ErrorCode.BAD_REQUEST, detail);
    }

    /** 路径不存在：不加此处理器会被下方的兜底分支吞成 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("资源不存在 [{} {}]", request.getMethod(), request.getRequestURI());
        return build(ErrorCode.NOT_FOUND, "接口不存在: " + request.getRequestURI());
    }

    /**
     * 客户端已经断开，响应写不进去了。
     *
     * <p>这不是服务端故障，是用户关页面、断网、切走之后的正常现象。Spring 6.1 起把它单独定义成
     * {@link AsyncRequestNotUsableException}，就是为了和真正的错误区分开。
     *
     * <p>必须显式处理：如果落到下面的兜底分支，处理器会尝试往一个已经断开的连接写 JSON，
     * 于是又抛一个"没有可用的消息转换器"，日志里就会出现两条与事实无关的 ERROR，
     * 真正的问题反而被埋起来了。这里只记 debug 并返回空响应。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("客户端已断开，响应不可用 [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
    }

    /**
     * IO 异常：只有当响应还没提交时才当作服务端故障。
     *
     * <p>响应一旦提交（流式接口在第一个事件之后就提交了），客户端断开会让任何写入都抛
     * {@code IOException}，此时既写不了 JSON 也改不了状态码。落到兜底分支的后果是：
     * 处理器再尝试写一次、再失败一次，日志里堆两条与技术事实无关的 ERROR。
     *
     * <p>返回 {@code null} 是 Spring 约定的"已处理、无需写内容"，不是遗漏。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> handleIOException(IOException ex, HttpServletRequest request,
                                                          HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("响应已提交，忽略写失败（通常是客户端断开）[{} {}]: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }
        log.error("IO 异常 [{} {}]", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    /** 兜底：未预期异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception ex, HttpServletRequest request) {
        log.error("未预期异常 [{} {}]", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<Result<Void>> build(ErrorCode errorCode, String message) {
        HttpStatus status = HttpStatus.resolve(errorCode.code());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(Result.<Void>failure(errorCode, message));
    }
}
