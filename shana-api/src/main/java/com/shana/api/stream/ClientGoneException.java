package com.shana.api.stream;

/**
 * 表示"客户端已经断开"的内部信号。
 *
 * <p>它不是业务错误，所以不经 {@code GlobalExceptionHandler}，也不会变成任何 HTTP 状态码——
 * 响应头早就发出去了。它唯一的作用是把调用栈从"往一个死连接写数据"的地方快速解开：
 * 抛出后模型客户端会关闭上游流，停止继续消耗 token。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常，是因为它要穿过
 * {@code Consumer<String>} 这类无法声明受检异常的函数式接口。
 */
public class ClientGoneException extends RuntimeException {

    public ClientGoneException(Throwable cause) {
        super("客户端已断开", cause, false, false);
    }
}
