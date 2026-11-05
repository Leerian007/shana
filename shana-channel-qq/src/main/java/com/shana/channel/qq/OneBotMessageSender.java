package com.shana.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 通过 OneBot 动作把文本发回 QQ。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li><b>消息段数组</b>而不是 CQ 码字符串。CQ 码里 {@code &}、{@code [} 都要转义，
 *       模型偶尔会吐出这些字符，一旦漏转义整条消息就废了；数组格式天生没有这个问题；</li>
 *   <li><b>第一条带 {@code reply} 段</b>。群里 @ 机器人之后，回复引用原消息能让上下文一目了然，
 *       也避免多个人同时提问时分不清在答谁；后续分段不再引用，否则每条都带引用条很吵；</li>
 *   <li><b>段间 sleep</b>。一口气连发四五条，QQ 侧的风控会把它当成刷屏，
 *       表现是"发出去但对方收不到"，比直接失败更难排查。</li>
 * </ul>
 */
public final class OneBotMessageSender implements QqMessageSender {

    private static final Logger log = LoggerFactory.getLogger(OneBotMessageSender.class);

    private final OneBotApi api;
    private final ObjectMapper objectMapper;
    private final ReplyChunker chunker;
    private final Duration interval;

    public OneBotMessageSender(OneBotApi api, ObjectMapper objectMapper, ReplyChunker chunker, Duration interval) {
        this.api = api;
        this.objectMapper = objectMapper;
        this.chunker = chunker;
        this.interval = interval;
    }

    @Override
    public void reply(IncomingMessage to, String content) {
        List<String> chunks = chunker.split(content);
        if (chunks.isEmpty()) {
            return;
        }
        for (int index = 0; index < chunks.size(); index++) {
            if (!sendOne(to, chunks.get(index), index == 0)) {
                // 一条发不出去，后面几条大概率也发不出去；继续尝试只会刷更多失败日志
                return;
            }
            if (index < chunks.size() - 1) {
                sleepBetweenChunks();
            }
        }
    }

    private boolean sendOne(IncomingMessage to, String chunk, boolean quoted) {
        ObjectNode params = objectMapper.createObjectNode();
        ArrayNode message = params.putArray("message");
        if (quoted && to.quotedMessageId() != null) {
            message.addObject()
                    .put("type", "reply")
                    .putObject("data")
                    .put("id", String.valueOf(to.quotedMessageId()));
        }
        message.addObject()
                .put("type", "text")
                .putObject("data")
                .put("text", chunk);

        String action;
        if (to.isGroup()) {
            action = "send_group_msg";
            params.put("group_id", to.groupId());
        } else {
            action = "send_private_msg";
            params.put("user_id", to.senderId());
        }

        try {
            api.call(action, params).join();
            return true;
        } catch (CompletionException e) {
            log.warn("发送 QQ 消息失败 conversation={} action={}：{}",
                    to.conversationId(), action, rootMessage(e));
            return false;
        } catch (RuntimeException e) {
            log.warn("发送 QQ 消息异常 conversation={} action={}", to.conversationId(), action, e);
            return false;
        }
    }

    private void sleepBetweenChunks() {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            // 停机或取消：不再继续发送剩下的分段，把中断标记还回去
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
