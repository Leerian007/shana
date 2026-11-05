package com.shana.api.controller;

import com.shana.api.dto.ChatRequestDTO;
import com.shana.api.stream.ClientGoneException;
import com.shana.api.stream.SseChatStreamHandler;
import com.shana.api.vo.ChatReplyVO;
import com.shana.common.api.ErrorCode;
import com.shana.common.api.Result;
import com.shana.common.exception.BusinessException;
import com.shana.core.chat.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.RejectedExecutionException;

/**
 * 群聊问答接口。
 *
 * <p>Controller 只做"收参数 → 调用服务 → 转响应"，业务编排在 {@link ChatService} 里。
 *
 * <p>提供两个入口，语义完全相同，区别只在"什么时候能看到字"：
 * <ul>
 *   <li>{@code POST /api/v1/chat} —— 生成完一次性返回，接入方最省事；</li>
 *   <li>{@code POST /api/v1/chat/stream} —— SSE 边生成边推，适合有界面的场景。</li>
 * </ul>
 * 两者共用同一套记忆与摘要逻辑，因此"用哪个接口"不影响上下文与统计数据。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /**
     * 显式声明 UTF-8：SSE 协议本身规定按 UTF-8 解码，但响应头里不写 charset 时，
     * 一部分客户端（以及测试里的 MockHttpServletResponse）会按 ISO-8859-1 处理，中文直接变乱码。
     * 声明出来不改变任何行为，只是省掉一次"为什么线上中文是问号"的排查。
     */
    static final String SSE_MEDIA_TYPE = "text/event-stream;charset=UTF-8";

    private final ChatService chatService;
    private final ThreadPoolTaskExecutor chatStreamExecutor;
    private final long streamTimeoutMs;

    public ChatController(ChatService chatService,
                          @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor chatStreamExecutor,
                          @Value("${shana.stream.timeout-ms:180000}") long streamTimeoutMs) {
        this.chatService = chatService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.streamTimeoutMs = streamTimeoutMs;
    }

    @PostMapping
    public Result<ChatReplyVO> chat(@Valid @RequestBody ChatRequestDTO request) {
        // senderId 只透传落库，不参与回复生成
        return Result.success(ChatReplyVO.from(
                chatService.reply(request.conversationId(), request.senderId(), request.message())));
    }

    /**
     * 流式问答：连接建立后先发 {@code meta}，随后是 {@code delta}/{@code tool}，
     * 最后以 {@code done} 或 {@code error} 结束。
     *
     * <p>返回 {@link SseEmitter} 而不是 {@code Flux}：本项目是 Spring MVC，
     * 为一个接口引入 WebFlux 不值得。代价是生成过程要自己找线程跑，见 {@link #produce}。
     *
     * <p>"还没开始生成"的错误（参数校验失败、线程池拒绝）仍然走统一的 JSON 异常响应——
     * 那时响应头还没提交，用 HTTP 状态码表达最清楚。一旦开始推事件，错误就只能走 {@code error} 事件。
     */
    @PostMapping(value = "/stream", produces = ChatController.SSE_MEDIA_TYPE)
    public SseEmitter stream(@Valid @RequestBody ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        SseChatStreamHandler handler = new SseChatStreamHandler(emitter);

        emitter.onTimeout(handler::onTimeout);
        emitter.onError(handler::onConnectionError);
        emitter.onCompletion(handler::markClosed);

        // 先发 meta 再提交任务：emitter 会把初始化前的事件缓存住，返回时一并刷出。
        // 顺序反过来的话，工作线程可能抢在前面推出 delta，客户端就会先看到正文、后看到会话号
        handler.start(request.conversationId());

        try {
            chatStreamExecutor.execute(() -> produce(request, handler));
        } catch (RejectedExecutionException ex) {
            // 线程池已满：这时还没有任何事件真正写出去，用 HTTP 503 表达比推一个 error 事件更准确
            handler.markClosed();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "当前并发过高，请稍后重试");
        }
        return emitter;
    }

    /** 在独立线程上跑完整生成过程，并把异常翻译成 {@code error} 事件。 */
    private void produce(ChatRequestDTO request, SseChatStreamHandler handler) {
        try {
            chatService.replyStream(request.conversationId(), request.senderId(), request.message(), handler);
        } catch (ClientGoneException ex) {
            // 客户端断开不是错误，只是没人听了：不再生成，也不制造日志噪音
            log.debug("客户端已断开，停止生成: conversationId={}", request.conversationId());
            // 主动结束异步上下文，避免容器稍后按"错误"再派发一次、撞上全局异常处理器
            handler.abortQuietly();
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException business) {
                log.warn("流式问答失败: conversationId={}, message={}",
                        request.conversationId(), business.getMessage());
            } else {
                log.error("流式问答异常: conversationId={}", request.conversationId(), ex);
            }
            handler.fail(ex);
        }
    }
}
