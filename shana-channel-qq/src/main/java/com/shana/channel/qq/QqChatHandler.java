package com.shana.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * QQ 消息 → 问答链路 → QQ 回复的编排。
 *
 * <p>顺序是刻意这样的，每一步都能独立解释：
 * <ol>
 *   <li><b>解析</b>：只认 {@code message} 事件，其余（通知、好友申请）直接丢；</li>
 *   <li><b>去重</b>：重连后可能重复推送，重复的不能再答一遍；</li>
 *   <li><b>准入</b>：自己发的、没 @ 的、非好友私聊都不该走到模型；</li>
 *   <li><b>聚合</b>：等一个短窗口，把"一句话拆三条"拼回来再问；</li>
 *   <li><b>回答</b>：放到独立线程池，模型调用要几秒，不能占住 WebSocket 的回调线程。</li>
 * </ol>
 *
 * <p>为什么不用流式：群聊里逐字输出会产生几十条消息，刷屏且无法撤回；
 * 一次性完整回答再分段发送，读起来更接近人的表达方式。流式留给 Web 端。
 */
public final class QqChatHandler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QqChatHandler.class);

    /** 失败时的兜底回复。@ 了却毫无反应，用户会以为机器人挂了。 */
    private static final String FAILURE_REPLY = "抱歉，刚才没能答上来，稍后再问我一次。";

    private final OneBotEventParser parser;
    private final ReplyPolicy policy;
    private final RecentMessages recentMessages = new RecentMessages();
    private final MessageAggregator aggregator;
    private final ChatService chatService;
    private final QqMessageSender sender;
    private final Executor executor;

    public QqChatHandler(OneBotEventParser parser,
                         ReplyPolicy policy,
                         ChatService chatService,
                         QqMessageSender sender,
                         Executor executor,
                         Duration debounceWindow,
                         int debounceMaxChars) {
        this.parser = parser;
        this.policy = policy;
        this.chatService = chatService;
        this.sender = sender;
        this.executor = executor;
        this.aggregator = new MessageAggregator(debounceWindow, debounceMaxChars, this::handle);
    }

    /** OneBot 事件入口，由 {@link OneBotWsClient#subscribe} 接到 WebSocket 回调线程。 */
    public void onEvent(JsonNode event) {
        parser.parse(event).ifPresent(message -> {
            if (!recentMessages.firstTime(message.messageId())) {
                log.debug("忽略重复消息 id={} conversation={}", message.messageId(), message.conversationId());
                return;
            }
            ReplyPolicy.Decision decision = policy.decide(message);
            if (!decision.allowed()) {
                log.debug("跳过 QQ 消息 conversation={} 原因={}", message.conversationId(), decision.rejection());
                return;
            }
            aggregator.accept(message);
        });
    }

    /** 聚合窗口到期后的实际处理。包级可见，便于单测直接驱动。 */
    void handle(IncomingMessage message) {
        try {
            executor.execute(() -> answer(message));
        } catch (RejectedExecutionException e) {
            log.warn("QQ 问答线程池已满，丢弃本次请求 conversation={}", message.conversationId());
        }
    }

    private void answer(IncomingMessage message) {
        long startedAt = System.nanoTime();
        try {
            ChatReply reply = chatService.reply(message.conversationId(),
                    String.valueOf(message.senderId()), message.text());
            sender.reply(message, reply.content());
            log.info("QQ 问答完成 conversation={} tools={} 耗时={}ms",
                    message.conversationId(), reply.toolsUsed(),
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("QQ 问答失败 conversation={}", message.conversationId(), e);
            sender.reply(message, FAILURE_REPLY);
        }
    }

    @Override
    public void close() {
        aggregator.close();
    }
}
