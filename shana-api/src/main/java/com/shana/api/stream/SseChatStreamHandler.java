package com.shana.api.stream;

import com.shana.api.vo.ChatReplyVO;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 把用例层的流式回调翻译成 SSE 事件。
 *
 * <p>这一层只做搬运动作，不含业务判断：事件名、载荷结构、什么时候关连接。
 * 换 WebSocket 或换前端框架时，只需要另写一个实现 {@link ChatStreamHandler} 的类。
 *
 * <h2>事件协议</h2>
 * <pre>
 * event: meta    {"conversationId":"g1"}                 连接建立，回显会话
 * event: delta   {"text":"你"}                            正文增量，可重复多次
 * event: reset   {"round":2}                              丢弃已展示内容，从头开始
 * event: tool    {"name":"group_message_stats"}           正在执行工具
 * event: done    {"content":"...","promptTokens":..}      结束，content 为落库的最终文本
 * event: error   {"code":503,"message":"..."}             失败
 * </pre>
 *
 * <p>{@code reset} 是这套协议里唯一"不直观但必须有"的事件：模型有时会先说一句
 * "我查一下"再调用工具，这段过渡语已经推给客户端了，但它不是答案、也不会落库。
 * 没有 reset，用户看到的和系统记住的就会不一致；有了它，客户端清掉气泡重画即可。
 *
 * <p><b>失败为什么不用 HTTP 状态码？</b>因为 SSE 的响应头在第一个事件发出时就已提交，
 * 之后再出错已经没有地方写状态码了。所以失败统一走 {@code error} 事件，
 * 客户端必须以事件为准，不能只看 HTTP 200。这个取舍是 SSE 协议本身决定的，不是实现偷懒。
 */
public final class SseChatStreamHandler implements ChatStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(SseChatStreamHandler.class);

    private final SseEmitter emitter;

    /** 一旦置位就不再写任何事件。客户端断开、超时、正常结束都会置位。 */
    private volatile boolean closed;

    public SseChatStreamHandler(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /** 连接建立后的第一个事件，让客户端立刻拿到反馈，而不是面对一个没有动静的连接。 */
    public void start(String conversationId) {
        send("meta", new Meta(conversationId));
    }

    @Override
    public void onRoundStart(int round) {
        if (round > 1) {
            // 第二轮开始意味着上一轮输出的只是过渡语，让客户端把它丢掉
            send("reset", new Reset(round));
        }
    }

    @Override
    public void onDelta(String text) {
        send("delta", new Delta(text));
    }

    @Override
    public void onTool(String toolName) {
        send("tool", new Tool(toolName));
    }

    /** 此时会话记忆已经写好，客户端收到后立刻追问也能接上上下文。 */
    @Override
    public void onComplete(ChatReply reply) {
        try {
            send("done", ChatReplyVO.from(reply));
        } finally {
            close();
        }
    }

    /**
     * 把失败翻译成 {@code error} 事件并结束连接。
     *
     * <p>已经断开时静默返回：错误事件送不到任何人手里，再抛异常只会污染日志。
     */
    public void fail(Throwable error) {
        if (closed) {
            return;
        }
        ErrorCode code = error instanceof BusinessException business
                ? business.getErrorCode()
                : ErrorCode.INTERNAL_ERROR;
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? code.message()
                : error.getMessage();
        try {
            send("error", new Failure(code.code(), message));
            log.warn("流式回答以错误结束: code={}, message={}", code.code(), message);
        } catch (ClientGoneException ex) {
            log.debug("发送错误事件时客户端已断开");
        } finally {
            close();
        }
    }

    /** 容器判定超时（客户端长时间不读，或生成卡住）。此时不再补事件，直接收尾。 */
    public void onTimeout() {
        closed = true;
        completeQuietly();
    }

    /** 容器报告连接异常（网络中断等）。 */
    public void onConnectionError(Throwable error) {
        closed = true;
        log.debug("SSE 连接异常: {}", error.toString());
    }

    /** 标记连接已结束，之后的写入一律跳过。 */
    public void markClosed() {
        closed = true;
    }

    /**
     * 客户端已经断开：不再写事件，但仍然要<b>把异步上下文结束掉</b>。
     *
     * <p>少了这一步，容器会在稍后自己发现连接没了，然后按"错误"重新派发一次请求，
     * 于是异常处理器试图往断掉的连接写 JSON，日志里出现两条与事实无关的 ERROR。
     * 这不是必须靠日志才能发现的问题——它会让真正的故障被噪音淹没。
     */
    public void abortQuietly() {
        closed = true;
        completeQuietly();
    }

    public boolean isClosed() {
        return closed;
    }

    /** 正常结束。 */
    private void close() {
        closed = true;
        completeQuietly();
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            // 连接已经不可用，关不掉属于预期之内；生成侧的错误已经单独处理过
            log.debug("结束 SSE 连接时响应已不可用: {}", ex.toString());
        }
    }

    private void send(String event, Object payload) {
        if (closed) {
            throw new ClientGoneException(null);
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // 写失败只有两种可能：连接没了，或者 emitter 已经结束。两种都不该继续生成
            closed = true;
            throw new ClientGoneException(ex);
        }
    }

    // ---------- 事件载荷 ----------
    // 用 record 而不是 Map：字段名一旦发布就是契约，record 能让"改字段"在编译期暴露出来

    record Meta(String conversationId) {
    }

    record Delta(String text) {
    }

    record Reset(int round) {
    }

    record Tool(String name) {
    }

    record Failure(int code, String message) {
    }
}
